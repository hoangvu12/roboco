package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.rememberHazeState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import kotlin.math.abs

/**
 * The chip goldens (android-ui-parity #59), recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.composer.ChipStripScreenshotTest`
 * and verified by `verifyRoborazziDebug`:
 *
 *  - the whole "Composer chips" gallery section in light and dark, on a
 *    400 × 1700 dp mdpi window (the section is ~1465 dp tall since
 *    android-next #01 added two context cards — was 1500 for ~1300; its
 *    registration line sets `screenshotHeightDp = 1600`);
 *  - the ticket's pixel checks: fixture B strip 1 on a plain white box in
 *    light — the untinted fill over white and the `PrOpen` tone wash over
 *    white are exact blends, and the PR chip's darkest pixel is the title
 *    green.
 *
 * `Glass.forceFallback = true` (the golden convention): the section's glass
 * cards and menus draw the fallback fill; nothing depends on how Robolectric
 * reports hardware acceleration. Paths are relative to the
 * `roborazzi { outputDir }` (`src/test/screenshots`), landing under
 * `composer/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h900dp-mdpi")
class ChipStripScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------- goldens

    private fun sectionShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    ChipStripGallerySection()
                }
            }
        }
        compose.waitForIdle()
        // One frame for fixture F's "scrolled to the end" strip: its
        // LaunchedEffect waits for a frame, then jumps to maxValue.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w400dp-h1700dp-mdpi")
    fun galleryLight() {
        sectionShot(Appearance.Light, "composer/composer_chips_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w400dp-h1700dp-mdpi")
    fun galleryDark() {
        sectionShot(Appearance.Dark, "composer/composer_chips_dark.png")
    }

    // -------------------------------------------------------- pixel checks

    /**
     * Fixture B strip 1 on a plain white box (not the gallery backdrop):
     * the untinted chip's fill is `controlFill` (`0x1327272C`) over white
     * ≈ #EFEFEF, the `PrOpen` chip's fill is `#15803D` @ 0x1A over white
     * ≈ #E7F2EB, and the darkest pixel inside the PR chip is its green
     * title ink.
     */
    @Test
    fun prOpenAndUntintedFillsBlendOverWhiteExactly() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    Box(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
                        ChipStrip(chipFixturePrStrips()[0], emptyMap(), canWrite = true, onChipTap = {})
                    }
                }
            }
        }
        compose.waitForIdle()

        // The left padding region (x < 11) is pure fill; y is the row centre.
        val model = pixels("composer-chip-model")
        val modelFill = channels(model[5, model.height / 2])
        assertNear("untinted fill over white", modelFill, floatArrayOf(239f, 239f, 239f), 2f)

        val pr = pixels("composer-chip-pr")
        val prFill = channels(pr[5, pr.height / 2])
        assertNear("PrOpen fill over white", prFill, floatArrayOf(231f, 242f, 235f), 2f)

        // The darkest pixel in the PR chip is the title (and icon) ink:
        // green-dominant, well darker than the fill.
        var darkestSum = Float.MAX_VALUE
        var darkest = floatArrayOf(0f, 0f, 0f)
        for (y in 0 until pr.height) {
            for (x in 0 until pr.width) {
                val p = channels(pr[x, y])
                val sum = p[0] + p[1] + p[2]
                if (sum < darkestSum) {
                    darkestSum = sum
                    darkest = p
                }
            }
        }
        assertTrue(
            "the PR chip's darkest pixel is green ink, was ${darkest.contentToString()}",
            darkest[1] - darkest[0] > 25f && darkest[1] - darkest[2] > 25f,
        )
        assertTrue(
            "the darkest pixel is far darker than the fill (was ${darkest.contentToString()})",
            darkestSum < prFill[0] + prFill[1] + prFill[2] - 60f,
        )
    }

    // ------------------------------------------------------------- helpers

    private fun pixels(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    private fun channels(pixel: Color): FloatArray =
        floatArrayOf(pixel.red * 255f, pixel.green * 255f, pixel.blue * 255f)

    private fun assertNear(where: String, actual: FloatArray, expected: FloatArray, tolerance: Float) {
        for (i in 0..2) {
            assertTrue(
                "$where: channel $i expected ${expected[i]} ± $tolerance, was ${actual[i]}",
                abs(actual[i] - expected[i]) <= tolerance,
            )
        }
    }
}
