package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rendering side of the wallpaper hero (android-ui-parity #21): the two
 * gallery goldens plus pixel checks of the mask, at mdpi where 1 dp = 1 px,
 * so the ticket's numbers apply directly. The section is mounted with
 * `showLive = false` (the live item reads the store; Robolectric has no
 * stored wallpaper).
 *
 * Geometry: the 600 × 400 fixture aspect-fills a 360 × 300 frame with scale
 * 0.75, so frame x maps to fixture source x `60 + x / 0.75` and frame y to
 * `y / 0.75`. Frame x = 90 is the middle of the orange band (150…300), x =
 * 270 the middle of the green band (300…450) — the centre column x = 180 is
 * exactly a band edge, so the profile check samples the two solid columns
 * flanking it instead. The 24-px diagonal stripes are 1 px + one bilinear
 * footprint away from every sampled pixel.
 *
 * Record with `:app:recordRoborazziDebug --tests sh.roboco.android.design.WallpaperHeroScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1100dp-mdpi")
class WallpaperHeroScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Frame 1's band at x = 90 (fixture source x 180, inside 150…300). */
    private val orange = Color(0xFFF5A524)

    /** Frame 1's band at x = 270 (fixture source x 420, inside 300…450). */
    private val green = Color(0xFF30A46C)

    // ------------------------------------------------------------ goldens

    private fun galleryShot(appearance: Appearance, file: String) {
        mount(appearance)
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun galleryLight() {
        galleryShot(Appearance.Light, "design/wallpaper_hero_light.png")
    }

    @Test
    fun galleryDark() {
        galleryShot(Appearance.Dark, "design/wallpaper_hero_dark.png")
    }

    // ------------------------------------------------------------ helpers

    private var appearance by mutableStateOf(Appearance.Light)
    private var mounted = false

    /**
     * Mounts the section once per test and flips [appearance] through state
     * afterwards: a second `setContent` on the rule throws "has already set
     * content", which is what the light-then-dark loops below hit.
     */
    private fun mount(appearance: Appearance) {
        if (mounted) {
            compose.runOnIdle { this.appearance = appearance }
            compose.waitForIdle()
            return
        }
        this.appearance = appearance
        mounted = true
        compose.setContent {
            RobocoTheme(this.appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    WallpaperHeroGallerySection(showLive = false)
                }
            }
        }
        compose.waitForIdle()
    }

    /** The [index]th wallpaper frame as pixels (360 × 300 at mdpi). */
    private fun frame(index: Int): PixelMap {
        val nodes = compose.onAllNodesWithTag("wallpaper").fetchSemanticsNodes()
        assertEquals("three fixture frames", 3, nodes.size)
        return compose.onAllNodesWithTag("wallpaper")[index].captureToImage().toPixelMap()
    }

    private fun channel(color: Color, ch: Int): Int = when (ch) {
        0 -> (color.red * 255f).roundToInt()
        1 -> (color.green * 255f).roundToInt()
        2 -> (color.blue * 255f).roundToInt()
        else -> error(ch)
    }

    private fun assertChannel(where: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$where: expected $expected ±$tolerance, was $actual", abs(expected - actual) <= tolerance)
    }

    /** `foreground × weight + background × (1 − weight)`, per channel. */
    private fun blend(foreground: Color, background: Color, weight: Float, ch: Int): Int =
        (channel(foreground, ch) * weight + channel(background, ch) * (1f - weight)).roundToInt()

    /**
     * Unblends the fixture's weight from a sampled pixel, averaging the
     * channels where fixture and background differ by ≥ 40 levels (the
     * others carry too little signal to divide by).
     */
    private fun fixtureWeight(sampled: Color, fixture: Color, background: Color): Float {
        var sum = 0f
        var usable = 0
        for (ch in 0..2) {
            val difference = channel(fixture, ch) - channel(background, ch)
            if (abs(difference) >= 40) {
                sum += (channel(sampled, ch) - channel(background, ch)).toFloat() / difference
                usable++
            }
        }
        assertTrue("no channel separates fixture and background", usable > 0)
        return sum / usable
    }

    // ------------------------------------------------------- frame 1

    @Test
    fun frame1FadesFromFullFixtureToBackground() {
        for (dark in listOf(false, true)) {
            mount(if (dark) Appearance.Dark else Appearance.Light)
            val background = Palette.of(dark).background
            val px = frame(0)

            // Top row: full strength (gradient alpha 1 at t → 0).
            for (ch in 0..2) {
                assertChannel("light=$dark top row x=90 channel $ch", channel(orange, ch), channel(px[90, 0], ch), 3)
                assertChannel("light=$dark top row x=270 channel $ch", channel(green, ch), channel(px[270, 0], ch), 3)
            }
            // 50 % of the height: the 50/50 blend of fixture and background.
            for (ch in 0..2) {
                assertChannel("light=$dark half height x=90 channel $ch", blend(orange, background, 0.5f, ch), channel(px[90, 150], ch), 3)
                assertChannel("light=$dark half height x=270 channel $ch", blend(green, background, 0.5f, ch), channel(px[270, 150], ch), 3)
            }
            // Bottom row: the page background (gradient alpha 0 at t = 1).
            for (ch in 0..2) {
                assertChannel("light=$dark bottom row x=90 channel $ch", channel(background, ch), channel(px[90, 299], ch), 2)
                assertChannel("light=$dark bottom row x=270 channel $ch", channel(background, ch), channel(px[270, 299], ch), 2)
            }
        }
    }

    @Test
    fun frame1FadeProfileMatchesTheSmoothstepCurve() {
        mount(Appearance.Light)
        val background = Palette.of(dark = false).background
        val px = frame(0)
        // Sampled down the two solid columns at 10 % steps (the centre
        // column is a fixture band edge). Max error ≤ 0.02 after unblending.
        val columns = listOf(90 to orange, 270 to green)
        for (step in 1..10) {
            val t = step / 10f
            val row = ((t * 300f) - 0.5f).roundToInt().coerceIn(0, 299)
            val expected = 1f - t * t * (3f - 2f * t)
            for ((x, band) in columns) {
                val sampled = fixtureWeight(px[x, row], band, background)
                assertTrue(
                    "light golden column x=$x t=$t: expected alpha $expected, unblended $sampled",
                    abs(sampled - expected) <= 0.02f,
                )
            }
        }
    }

    // ------------------------------------------------------- frame 2

    @Test
    fun frame2BlendsTheTopRowAtThirtyPercent() {
        for (dark in listOf(false, true)) {
            mount(if (dark) Appearance.Dark else Appearance.Light)
            val background = Palette.of(dark).background
            val px = frame(1)
            // Top row: opacity 0.6 × scrollFade 0.5 = a 30 % blend.
            for (ch in 0..2) {
                assertChannel("light=$dark top row x=90 channel $ch", blend(orange, background, 0.3f, ch), channel(px[90, 0], ch), 3)
                assertChannel("light=$dark top row x=270 channel $ch", blend(green, background, 0.3f, ch), channel(px[270, 0], ch), 3)
            }
        }
    }

    // ------------------------------------------------------- frame 3

    @Test
    fun frame3HalvesTheArtworkInsideTheFeatheredCutout() {
        for (dark in listOf(false, true)) {
            mount(if (dark) Appearance.Dark else Appearance.Light)
            val background = Palette.of(dark).background
            val px = frame(2)

            // The cutout centre (180, 206) sits on the orange/green band
            // edge, so the weight is unblended from the solid orange column
            // at x = 158 instead: its source x 271.3 sits midway between two
            // white stripes on rows 206 and 242 (x = 150 lands 0.9 source px
            // from a stripe's centre line on those rows, and the white it
            // picks up read as a weight of 0.032). t = 206.5/300 = 0.688 → gradient alpha
            // 0.231; hero height 300 → feather 52 dp, σ = 26 dp; the outset
            // rect is 344 × 68 dp, so the blurred coverage at its centre is
            // G ≈ 2Φ(34/26) − 1 ≈ 0.81 and m = (1 − 0.25·G) × 0.5 ≈ 0.40 —
            // the fixture's weight is m × 0.233 ≈ 0.093 (frame 1: 0.233).
            val weight = fixtureWeight(px[158, 206], orange, background)
            assertTrue(
                "light=$dark cutout centre: expected fixture weight ≈ 0.093, was $weight",
                abs(weight - 0.093f) <= 0.02f,
            )

            // The soft step at the outset edge: just outside the rect the
            // weight nearly doubles (0.875·a vs 0.4375·a) — sampled 4 px
            // either side of the 8 dp outset (y = 172…240).
            val inside = fixtureWeight(px[158, 238], orange, background)
            val outside = fixtureWeight(px[158, 242], orange, background)
            assertTrue(
                "light=$dark the cutout's hard step: inside $inside, outside $outside",
                outside > inside * 1.5f,
            )
        }
    }
}
