package sh.roboco.android.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The meta-glyphs goldens and node checks (android-ui-parity #12): the
 * ticket's fixture rendered in light and dark, the badge's measured dp
 * (its own text measurement feeding the intrinsic size), its single a11y
 * node, the tone mapping, and the tile's two render forms
 * (pixel-identical, theme-following, fill tone @ 0.08).
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp), `w400dp-h800dp`
 * as the ticket pins. Roborazzi paths are relative to
 * `roborazzi { outputDir }` (`src/test/screenshots`), so the goldens land
 * at `src/test/screenshots/design/meta_glyphs_{light,dark}.png`; recorded
 * by `recordRoborazziDebug`, verified by `verifyRoborazziDebug`. Ticket
 * 22's parameterized `GalleryScreenshotTest` also captures the registered
 * section as `design/gallery/gallery_meta_glyphs_{light,dark}.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-xxhdpi")
class MetaGlyphsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent { MetaGlyphsShotHost(appearance) }
        compose.onRoot().captureRoboImage(file)
    }

    /** The golden host: the section on its palette background (no frame). */
    @Composable
    private fun MetaGlyphsShotHost(appearance: Appearance) {
        RobocoTheme(appearance) {
            Box(Modifier.background(RobocoTheme.palette.background)) { MetaGlyphsGallerySection() }
        }
    }

    @Test
    fun lightGolden() {
        shot(Appearance.Light, "design/meta_glyphs_light.png")
    }

    @Test
    fun darkGolden() {
        shot(Appearance.Dark, "design/meta_glyphs_dark.png")
    }

    // ------------------------------------------------- measured badge sizes

    @Test
    fun badgeAtElevenMeasuresThirtyEightByEighteenDp() {
        compose.setContent { RobocoTheme(Appearance.Light) { PrBadge(77u, PrState.Closed) } }
        compose.onNodeWithContentDescription("Pull request 77, closed")
            .assertWidthIsEqualTo(38.dp)
            .assertHeightIsEqualTo(18.dp)
    }

    @Test
    fun badgeOneOhTwoAtElevenMeasuresFortyFourByEighteenDp() {
        compose.setContent { RobocoTheme(Appearance.Light) { PrBadge(102u, PrState.Open) } }
        compose.onNodeWithContentDescription("Pull request 102, open")
            .assertWidthIsEqualTo(44.dp)
            .assertHeightIsEqualTo(18.dp)
    }

    @Test
    fun badgeAtFourteenMeasuresFiftyFiveByTwentyThreeDp() {
        compose.setContent { RobocoTheme(Appearance.Light) { PrBadge(102u, PrState.Open, size = 14f) } }
        compose.onNodeWithContentDescription("Pull request 102, open")
            .assertWidthIsEqualTo(55.dp)
            .assertHeightIsEqualTo(23.dp)
    }

    @Test
    fun badgeIsOneA11yNodeWithNoSeparateTextNode() {
        compose.setContent { RobocoTheme(Appearance.Light) { PrBadge(102u, PrState.Open) } }
        // The number is not its own semantics node: the badge node speaks
        // the whole label (SessionCell appends prBadge.accessibilityLabel).
        compose.onNodeWithContentDescription("Pull request 102, open").assertExists()
        compose.onNodeWithText("102").assertDoesNotExist()
    }

    // --------------------------------------------------------- tone mapping

    @Test
    fun prToneMapsStatesToPaletteRolesInLightAndDark() {
        val tones = HashMap<Appearance, List<Color>>()
        for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
            compose.setContent {
                RobocoTheme(appearance) {
                    val list = listOf(
                        prTone(PrState.Open),
                        prTone(PrState.Merged),
                        prTone(PrState.Closed),
                        prTone(PrState.Draft),
                    )
                    SideEffect { tones[appearance] = list }
                    Box(Modifier.size(1.dp)) {}
                }
            }
            compose.waitForIdle()
        }
        for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
            val palette = Palette.of(appearance == Appearance.Dark)
            val list = tones.getValue(appearance)
            assertEquals("open s=$appearance", palette.success, list[0])
            assertEquals("merged s=$appearance", palette.accent, list[1])
            assertEquals("closed s=$appearance", palette.danger, list[2])
            assertEquals("draft s=$appearance", palette.secondary, list[3])
        }
    }

    // --------------------------------------------------------- tile pixels

    @Test
    fun tileComposableAndBitmapPainterArePixelIdentical() {
        // Both forms go through ProjectTiles.draw; at side 14 the bitmap
        // form draws 1:1 (42 px at xxhdpi).
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Row {
                    ProjectTile("roboco", 2, Modifier.testTag("direct"), side = 14.dp)
                    Image(
                        painter = rememberProjectTilePainter("roboco", 2, 14.dp),
                        contentDescription = null,
                        modifier = Modifier.testTag("painter").size(14.dp),
                    )
                }
            }
        }
        val direct = compose.onNodeWithTag("direct").captureToImage().toPixelMap()
        val painter = compose.onNodeWithTag("painter").captureToImage().toPixelMap()
        assertEquals(direct.width, painter.width)
        assertEquals(direct.height, painter.height)
        assertTrue(
            "the composable and the bitmap painter differ",
            direct.buffer.contentEquals(painter.buffer),
        )
    }

    @Test
    fun tileBitmapSwitchesToTheDarkToneWithTheTheme() {
        var dark by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(if (dark) Appearance.Dark else Appearance.Light) {
                ProjectTile("roboco", 2, Modifier.testTag("tile"), side = 14.dp)
            }
        }
        val light = compose.onNodeWithTag("tile").captureToImage().toPixelMap()
        compose.runOnIdle { dark = true }
        val darkPx = compose.onNodeWithTag("tile").captureToImage().toPixelMap()
        assertFalse(
            "the tile did not re-render with the dark tone",
            light.buffer.contentEquals(darkPx.buffer),
        )
    }

    @Test
    fun tileFillIsTheToneAtEightPercentForAllTonesInLightAndDark() {
        // Sample a fill-only pixel (centre-x, 4 px down — above the letter's
        // cap, which starts ~24 px down at side 42) and check it equals the
        // background blended with the tone at 20/255, within ±2/255.
        var colorIndex by mutableStateOf(0)
        var dark by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(if (dark) Appearance.Dark else Appearance.Light) {
                val palette = RobocoTheme.palette
                Box(Modifier.testTag("host").background(palette.background)) {
                    ProjectTile("roboco", colorIndex, Modifier, side = 14.dp)
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            val palette = Palette.of(mode)
            for (i in 0 until Palette.PROJECT_TONE_COUNT) {
                compose.runOnIdle { colorIndex = i }
                val px = compose.onNodeWithTag("host").captureToImage().toPixelMap()
                assertBlendedFill(px[21, 4], palette.background, palette.projectTone(i))
            }
        }
    }

    @Test
    fun tileColorIndexWrapsByFloorMod() {
        // colorIndex 9 renders as 1 and −1 as 7: the same tone, byte-for-byte.
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Row {
                    ProjectTile("roboco", 1, Modifier.testTag("one"), side = 14.dp)
                    ProjectTile("roboco", 9, Modifier.testTag("nine"), side = 14.dp)
                    ProjectTile("roboco", 7, Modifier.testTag("seven"), side = 14.dp)
                    ProjectTile("roboco", -1, Modifier.testTag("minus-one"), side = 14.dp)
                }
            }
        }
        val one = compose.onNodeWithTag("one").captureToImage().toPixelMap()
        val nine = compose.onNodeWithTag("nine").captureToImage().toPixelMap()
        val seven = compose.onNodeWithTag("seven").captureToImage().toPixelMap()
        val minusOne = compose.onNodeWithTag("minus-one").captureToImage().toPixelMap()
        assertTrue("9 should render like 1", one.buffer.contentEquals(nine.buffer))
        assertTrue("-1 should render like 7", seven.buffer.contentEquals(minusOne.buffer))
        assertFalse("1 and 7 are different tones", one.buffer.contentEquals(seven.buffer))
    }

    @Test
    fun badgeFillIsTheToneAtEightPercentForEveryStateInLightAndDark() {
        // Sample a fill-only pixel of the badge (7 px in from the left edge,
        // mid-height — left of the icon at x = pad = 15 px, away from the
        // rounded corners' curvature) and check the background blended with
        // the tone at 20/255, within ±2/255. H = 18 dp = 54 px at xxhdpi.
        // The tone is the real prTone(state), captured in composition.
        var state by mutableStateOf(PrState.Open)
        var dark by mutableStateOf(false)
        val tones = HashMap<PrState, Color>()
        compose.setContent {
            RobocoTheme(if (dark) Appearance.Dark else Appearance.Light) {
                val palette = RobocoTheme.palette
                val tone = prTone(state)
                SideEffect { tones[state] = tone }
                Box(Modifier.testTag("host").background(palette.background)) {
                    PrBadge(102u, state)
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            val palette = Palette.of(mode)
            for (candidate in PrState.entries) {
                compose.runOnIdle { state = candidate }
                val px = compose.onNodeWithTag("host").captureToImage().toPixelMap()
                assertBlendedFill(px[7, 27], palette.background, tones.getValue(candidate))
            }
        }
    }

    private fun assertBlendedFill(actual: Color, background: Color, tone: Color) {
        val a = ProjectTiles.FILL_ALPHA / 255f
        fun near(name: String, expected: Float, got: Float) {
            assertTrue(
                "$name: expected ~${expected * 255f}, got ${got * 255f}",
                abs(expected - got) * 255f <= 2f,
            )
        }
        assertEquals(1f, actual.alpha, 0.01f)
        near("red", background.red * (1f - a) + tone.red * a, actual.red)
        near("green", background.green * (1f - a) + tone.green * a, actual.green)
        near("blue", background.blue * (1f - a) + tone.blue * a, actual.blue)
    }
}
