package sh.roboco.android.newsession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The locations rail and the search/typed-path field's geometry, taps,
 * IME behaviour and a11y (android-ui-parity #112) — Robolectric +
 * compose-ui-test at **mdpi** (`w402dp-h560dp-mdpi`, font scale 1.0),
 * where 1 dp = 1 px and the ticket's dp tables apply directly.
 *
 * The harness wraps the header in a tagged `Column` so the whole 90 dp
 * block is measurable, paints the plain `background` (a separate pixel
 * test paints white so the fills are exact blends), and hoists the query
 * into the test so `performTextInput` recomposes through the real
 * `onValueChange`. The fixtures come from the debug gallery file
 * (`folderLocationsFixtures`, shared verbatim with the screenshots).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h560dp-mdpi")
class FolderLocationsHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    @After
    fun unfreeze() {
        compose.mainClock.autoAdvance = true
    }

    // ------------------------------------------------------------- harness

    private val fixtureA: FolderLocationsSpec get() = folderLocationsFixtureA()

    /** Places the header, hoisting the query when [interactive] (for typing). */
    private fun place(
        spec: FolderLocationsSpec,
        appearance: Appearance = Appearance.Light,
        onPick: (LocationChipModel) -> Unit = {},
        onQueryChange: (String) -> Unit = {},
        onGo: () -> Unit = {},
        interactive: Boolean = false,
        background: Color? = null,
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(background ?: RobocoTheme.palette.background)) {
                    Column(Modifier.testTag("header-host")) {
                        if (interactive) {
                            var text by remember { mutableStateOf(spec.query) }
                            FolderLocationsHeader(
                                chips = spec.chips,
                                selected = spec.selected,
                                query = text,
                                enabled = spec.enabled,
                                onPickLocation = onPick,
                                onQueryChange = {
                                    onQueryChange(it)
                                    text = it
                                },
                                onGo = onGo,
                            )
                        } else {
                            FolderLocationsHeader(
                                chips = spec.chips,
                                selected = spec.selected,
                                query = spec.query,
                                enabled = spec.enabled,
                                onPickLocation = onPick,
                                onQueryChange = onQueryChange,
                                onGo = onGo,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    // ----------------------------------------------------------- geometry

    @Test
    fun theRailIs34DpTallSpansTheWidthAndStarts16DpIn() {
        place(fixtureA)
        val rail = bounds("folder-locations")
        assertEquals(34.dp, rail.height)
        assertEquals(0.dp, rail.left)

        val home = bounds("location-home")
        assertEquals("the first chip is flush with the list cards at scroll 0", 16.dp, home.left)

        // Chips are 6 dp apart: the gap between the Home chip's right edge
        // and the System chip's left edge.
        val system = bounds("location-drive-/")
        assertEquals(6.dp, system.left - home.right)
    }

    @Test
    fun theFieldIs40DpTallAndThePlaceholderStarts12DpInsideTheFieldBox() {
        place(fixtureA, background = Color.White)
        val field = bounds("folder-search")
        assertEquals(40.dp, field.height)
        // The node's bounds are the field box itself: its first semantics
        // modifier (the tag) sits after the 16 dp margins, so x = 0 is the
        // box's leading edge and the placeholder's first glyph ≈ 12 (the
        // fill tests below sample width - 20 for the same reason). Scan the
        // field's centre row for the first pixel that leaves the fill.
        val map = pixels("folder-search")
        val fill = channels(map[map.width - 20, map.height / 2])
        // The leftmost ink over the text band, not the centre row alone:
        // the first glyph is an `S`, whose mid-height spine starts ~4 px
        // right of the glyph's left edge (the centre row alone read 16).
        var glyphStart = -1
        for (y in map.height / 4..map.height * 3 / 4) {
            for (x in 2 until map.width / 2) {
                val p = channels(map[x, y])
                if (abs(p[0] - fill[0]) > 12f || abs(p[1] - fill[1]) > 12f || abs(p[2] - fill[2]) > 12f) {
                    if (glyphStart < 0 || x < glyphStart) glyphStart = x
                    break
                }
            }
        }
        assertTrue("the placeholder starts 12 dp inside the field box (≈12), was $glyphStart", glyphStart in 9..15)
    }

    @Test
    fun theBlockMeasures90DpFromItsTopToTheFieldBottom() {
        place(fixtureA)
        val host = bounds("header-host")
        val rail = bounds("folder-locations")
        val field = bounds("folder-search")
        assertEquals(90.dp, host.height)
        assertEquals("8 dp of top padding below the nav bar", 8.dp, rail.top - host.top)
        assertEquals("the rail and the field are 8 dp apart", 8.dp, field.top - rail.bottom)
        assertEquals(90.dp, field.bottom - host.top)
    }

    // ------------------------------------------------------ a11y + state

    @Test
    fun theSelectedChipCarriesTheSelectedSemantics() {
        place(fixtureA)
        compose.onNodeWithTag("location-home").assertIsSelected()
        compose.onNodeWithTag("location-drive-/").assertIsNotSelected()
        compose.onNodeWithTag("location-drive-/mnt/data").assertIsNotSelected()
    }

    @Test
    fun theEmptyFieldCarriesThePlaceholderAsContentDescription() {
        place(fixtureA)
        assertEquals(
            FolderHeaderTokens.Placeholder,
            compose.onNodeWithTag("folder-search").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
        )
    }

    // ------------------------------------------------------------- taps

    @Test
    fun tappingAChipReportsThatChipModel() {
        val picked = mutableListOf<LocationChipModel>()
        place(fixtureA, onPick = { picked += it })
        compose.onNodeWithTag("location-drive-/mnt/data").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LocationChipModel("data", "/mnt/data")), picked)
        compose.onNodeWithTag("location-home").performClick()
        compose.waitForIdle()
        assertEquals(LocationChipModel("Home", null), picked[1])
    }

    @Test
    fun whileSubmittingTheChipsAreDisabledAndNoOp() {
        val picked = mutableListOf<LocationChipModel>()
        place(folderLocationsFixtureD(), onPick = { picked += it })
        compose.onNodeWithTag("location-home").assertIsNotEnabled()
        compose.onNodeWithTag("location-drive-/").assertIsNotEnabled()
        compose.onNodeWithTag("location-drive-/mnt/data").performClick()
        compose.waitForIdle()
        assertTrue(picked.isEmpty())
    }

    // -------------------------------------------------------------- IME

    @Test
    fun typingEditsTheValueAndGoClearsFocusAndFiresOnce() {
        val changes = mutableListOf<String>()
        var goCount = 0
        place(fixtureA, onQueryChange = { changes += it }, onGo = { goCount++ }, interactive = true)
        compose.onNodeWithTag("folder-search").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-search").assertIsFocused()

        compose.onNodeWithTag("folder-search").performTextInput("doc")
        compose.waitForIdle()
        assertEquals("doc", changes.lastOrNull())

        compose.onNodeWithTag("folder-search").performImeAction()
        compose.waitForIdle()
        assertEquals(1, goCount)
        compose.onNodeWithTag("folder-search").assertIsNotFocused()
    }

    @Test
    fun whileSubmittingTheFieldTakesNoInput() {
        val changes = mutableListOf<String>()
        place(folderLocationsFixtureD(), onQueryChange = { changes += it })
        compose.onNodeWithTag("folder-search").assertIsNotEnabled()
        // The node still exists (editable text, disabled).
        compose.onNodeWithTag("folder-search").assertExists()
        assertTrue(changes.isEmpty())
    }

    // ------------------------------------------------- the fills, exactly

    /**
     * Fixture A on a plain white box: the selected Home chip's fill is
     * `accentSoft` (`0x1F5B43E8`) over white ≈ (235, 232, 252), the
     * unselected chips are `controlFill` (`0x1327272C`) over white ≈
     * (239, 239, 239), and the field's fill is `chip` @ 0.6 (`0x99E7E7EB`)
     * over white ≈ (241, 241, 243). In dark the field fill is `0x991C1C20`
     * over white ≈ (119, 119, 121). All sampled left of the title, at the
     * chip's/field's vertical centre.
     */
    @Test
    fun theFillsBlendOverWhiteExactly() {
        place(fixtureA, background = Color.White)
        val home = pixels("location-home")
        assertNear("selected Home fill over white", channels(home[5, home.height / 2]), floatArrayOf(235f, 232f, 252f), 2f)
        val system = pixels("location-drive-/")
        assertNear("unselected fill over white", channels(system[5, system.height / 2]), floatArrayOf(239f, 239f, 239f), 2f)
        val field = pixels("folder-search")
        // Past the placeholder text, inside the 16..386 dp field box.
        assertNear("field fill over white", channels(field[field.width - 20, field.height / 2]), floatArrayOf(241f, 241f, 243f), 2f)
    }

    @Test
    fun theDarkFieldFillBlendsOverWhiteExactly() {
        place(fixtureA, appearance = Appearance.Dark, background = Color.White)
        val field = pixels("folder-search")
        assertNear("dark field fill over white", channels(field[field.width - 20, field.height / 2]), floatArrayOf(119f, 119f, 121f), 2f)
    }

    /**
     * The darkest pixel inside the selected Home chip is its `accent` ink
     * (violet-dominant), not the neutral `text` of an unselected title.
     */
    @Test
    fun theSelectedChipInkIsTheAccent() {
        place(fixtureA, background = Color.White)
        val home = pixels("location-home")
        var darkest = floatArrayOf(255f, 255f, 255f)
        var darkestSum = Float.MAX_VALUE
        for (y in 0 until home.height) {
            for (x in 0 until home.width) {
                val p = channels(home[x, y])
                val sum = p[0] + p[1] + p[2]
                if (sum < darkestSum) {
                    darkestSum = sum
                    darkest = p
                }
            }
        }
        assertTrue(
            "the selected chip's darkest pixel is violet ink, was ${darkest.contentToString()}",
            darkest[2] - darkest[1] > 80f,
        )
    }

    // ----------------------------------------------------------- helpers

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
