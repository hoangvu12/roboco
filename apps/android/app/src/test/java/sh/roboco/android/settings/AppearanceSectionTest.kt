package sh.roboco.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.settingsTag

/**
 * The Appearance section (android-ui-parity #96) through the real
 * [AppearanceRowsColumn] / [appearanceSection] /
 * [rememberAppearanceSectionState]:
 *
 *  - the ticket 70 geometry at this section's call sites, on the ticket's
 *    `w402dp` phone at xxhdpi (1 dp = 3 px), font scale 1: the 57.8 dp
 *    header block, three 52 dp rows in a 156 dp card, the icon boxes at
 *    x 32…56, the titles at x 72, separators under rows 0 and 1 only;
 *  - the iOS copy, verbatim, and the tags `settings-appearance:0/1/2`;
 *  - the checkmark: `Selected` semantics on exactly the row whose stored
 *    value is current — from the section's `current`, never
 *    `RobocoTheme.isDark` — and the accent-pixel probe (±16 per channel
 *    of `#5B43E8`, the bounding-box centre at 341.5 dp in row
 *    coordinates) finding it in the checked row only;
 *  - the tap: `onPick` fires once, the checkmark moves on the very next
 *    frame with no intermediate frames (frozen clock), the checked row
 *    tap is a visual no-op;
 *  - the lazy emitter over the remembered state: a tap records the pick,
 *    an external flow change moves the checkmark with no user input.
 *
 * Written per the test policy; the batch/VPS run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h400dp-xxhdpi")
class AppearanceSectionTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ helpers

    /** The section, the ticket's mounting: a 402 dp column on the page background. */
    private fun mount(
        appearance: Appearance = Appearance.Light,
        current: Appearance = Appearance.System,
        onPick: (Appearance) -> Unit = {},
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                AppearanceRowsColumn(
                    current = current,
                    onPick = onPick,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag)

    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    private fun isHeading(): SemanticsMatcher =
        SemanticsMatcher("is a heading") { it.config.contains(SemanticsProperties.Heading) }

    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual, expected $expected ±$tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun rowImage(tag: String): PixelMap = row(tag).captureToImage().toPixelMap()

    private fun channel(color: Color, ch: Int): Int = when (ch) {
        0 -> (color.red * 255f).toInt()
        1 -> (color.green * 255f).toInt()
        2 -> (color.blue * 255f).toInt()
        else -> error(ch)
    }

    private fun matches(c: Color, r: Int, g: Int, b: Int, tolerance: Int): Boolean =
        abs(channel(c, 0) - r) <= tolerance && abs(channel(c, 1) - g) <= tolerance && abs(channel(c, 2) - b) <= tolerance

    /**
     * The accent-ink pixels within [tolerance] per channel of (r, g, b):
     * their count and their bounding box. The acceptance's checkmark
     * probe — the ink is centred, so a centroid would do too, but the
     * ticket asks for the bounding box.
     */
    private class InkBox(val count: Int, val minX: Int, val maxX: Int)

    private fun inkBox(px: PixelMap, r: Int, g: Int, b: Int, tolerance: Int): InkBox {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var count = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (matches(px[x, y], r, g, b, tolerance)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    count++
                }
            }
        }
        return InkBox(count, minX, maxX)
    }

    /** The accent `#5B43E8` bounding-box centre, in dp of the row's own coordinates. */
    private fun accentCentreDp(tag: String, r: Int, g: Int, b: Int): Float {
        val box = inkBox(rowImage(tag), r, g, b, 16)
        assertTrue("no accent pixels in $tag", box.count > 0)
        val centrePx = (box.minX + box.maxX) / 2f
        return centrePx / compose.density.density
    }

    private fun accentCount(tag: String, r: Int, g: Int, b: Int): Int =
        inkBox(rowImage(tag), r, g, b, 16).count

    // ---------------------------------------------------------- geometry

    @Test
    fun theSectionGeometryMatchesTheTicket() {
        mount()
        val row0 = row(settingsTag("appearance:0")).getUnclippedBoundsInRoot()
        val row1 = row(settingsTag("appearance:1")).getUnclippedBoundsInRoot()
        val row2 = row(settingsTag("appearance:2")).getUnclippedBoundsInRoot()
        assertDp(row0.left, 16.dp, "card left")
        assertDp(row0.right, 386.dp, "card right")
        assertDp(row0.top, 57.8.dp, "the header block above the card (27 + 20.8 + 10)")
        assertDp(heightOf(row0), 52.dp, "row 0 height (title only)")
        assertDp(heightOf(row1), 52.dp, "row 1 height")
        assertDp(heightOf(row2), 52.dp, "row 2 height")
        assertDp(row1.top - row0.bottom, 0.dp, "no gap between rows 0 and 1", 0.5f)
        assertDp(row2.top - row1.bottom, 0.dp, "no gap between rows 1 and 2", 0.5f)
        assertDp(row2.bottom - row0.top, 156.dp, "card height (3 × 52)")

        val header = compose.onNodeWithText("Appearance", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(header.left, 32.dp, "header text left")
        assertDp(header.top, 27.dp, "header text top")
        assertDp(heightOf(header), 20.8.dp, "header line box")

        assertDp(
            compose.onNodeWithText("System", useUnmergedTree = true).getUnclippedBoundsInRoot().left,
            72.dp,
            "row 0 title left",
        )
        assertDp(
            compose.onNodeWithText("Light", useUnmergedTree = true).getUnclippedBoundsInRoot().left,
            72.dp,
            "row 1 title left",
        )
        assertDp(
            compose.onNodeWithText("Dark", useUnmergedTree = true).getUnclippedBoundsInRoot().left,
            72.dp,
            "row 2 title left",
        )
    }

    @Test
    fun theIconBoxesSpanX32To56() {
        mount()
        // A 52 dp row is 156 px: the icon band is the row's centre ± 12 dp
        // (78 ± 36 px); the icon box spans 16…40 dp into the card (image
        // x 48…120 = screen x 32…56). The leading glyphs sample secondary
        // (#62626A in light).
        for (tag in 0..2) {
            val px = rowImage(settingsTag("appearance:$tag"))
            var icon = 0
            for (y in 42..114) {
                for (x in 48..120) {
                    if (matches(px[x, y], 98, 98, 106, 8)) icon++
                }
            }
            assertTrue("row $tag: the leading symbol samples secondary", icon > 4)
        }
        // The gap between the icon box's end (56) and the title (72) is blank.
        val row0 = rowImage(settingsTag("appearance:0"))
        for (x in 128..165) {
            for (y in 42..114) {
                assertTrue("stray ink at ($x, $y) between the icon box and the title", row0[x, y].red > 0.6f)
            }
        }
    }

    @Test
    fun aSeparatorRunsUnderRowsZeroAndOneOnly() {
        mount()
        // UIKit separator over white ≈ (198, 198, 200), on the row's last
        // pixel row, 56 dp into the card to 16 dp before its edge —
        // the separator follows the icon: x 72…370 in screen coordinates.
        for (tag in 0..1) {
            val px = rowImage(settingsTag("appearance:$tag"))
            assertTrue("row $tag separator start (56 dp into the card)", matches(px[168, 155], 198, 198, 200, 12))
            assertTrue("row $tag left of the separator start", matches(px[160, 155], 255, 255, 255, 2))
            // The hairline is drawn over [168, 1062) px with butt caps:
            // x = 1062 px (354 dp) is its exclusive end, so the last lit
            // pixel is 1061.
            assertTrue("row $tag separator end (16 dp before the card edge)", matches(px[1061, 155], 198, 198, 200, 12))
            assertTrue("row $tag right of the separator end", matches(px[1068, 155], 255, 255, 255, 2))
        }
        val row2 = rowImage(settingsTag("appearance:2"))
        assertTrue("no separator under the last row", matches(row2[300, 155], 255, 255, 255, 2))
        assertTrue("no separator under the last row (right)", matches(row2[1000, 155], 255, 255, 255, 2))
    }

    // ------------------------------------------------------- copy + a11y

    @Test
    fun theCopyIsVerbatimAndTheHeaderIsAHeading() {
        mount()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("System").assertExists()
        compose.onNodeWithText("Light").assertExists()
        compose.onNodeWithText("Dark").assertExists()
        assertEquals("only the Appearance header is a heading", 1, compose.onAllNodes(isHeading()).fetchSemanticsNodes().size)
    }

    @Test
    fun theRowsAreButtonsWithTheTitleAsMergedText() {
        mount()
        for (i in 0..2) {
            val node = row(settingsTag("appearance:$i"))
            assertEquals(Role.Button, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
            node.assertHasClickAction()
            // Icons and the checkmark are decorative: the merged text is
            // exactly the title.
            val texts = node.fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString(separator = "") { it.text }
                ?: ""
            assertEquals(AppearanceRows.all[i].title, texts)
        }
    }

    @Test
    fun onlyTheRowWhoseStoredValueIsCurrentIsSelected() {
        mount(current = Appearance.System)
        assertEquals("exactly one selected row", 1, compose.onAllNodes(selected).fetchSemanticsNodes().size)
        row(settingsTag("appearance:0")).assert(selected)
        assertFalse(
            "row 1 has no Selected property",
            SemanticsProperties.Selected in row(settingsTag("appearance:1")).fetchSemanticsNode().config,
        )
        assertFalse(
            "row 2 has no Selected property",
            SemanticsProperties.Selected in row(settingsTag("appearance:2")).fetchSemanticsNode().config,
        )
    }

    @Test
    fun theCheckmarkFollowsTheStoredValueNotTheTheme() {
        // The theme is forced light; the stored value is Dark — like
        // System-on-a-dark-phone, the row follows the preference.
        mount(appearance = Appearance.Light, current = Appearance.Dark)
        row(settingsTag("appearance:2")).assert(selected)
        assertEquals("exactly one selected row", 1, compose.onAllNodes(selected).fetchSemanticsNodes().size)
        val centre = accentCentreDp(settingsTag("appearance:2"), 91, 67, 232)
        assertTrue("checkmark centre $centre dp, expected 341.5 ± 1", abs(centre - 341.5f) <= 1f)
        assertEquals("row 0 has no checkmark", 0, accentCount(settingsTag("appearance:0"), 91, 67, 232))
        assertEquals("row 1 has no checkmark", 0, accentCount(settingsTag("appearance:1"), 91, 67, 232))
    }

    @Test
    fun theCheckmarkInkCentreIsAt341Point5DpInRowCoordinates() {
        mount(current = Appearance.System)
        // The ink centre is 28.5 dp from the card's trailing edge: 386 −
        // 28.5 = 357.5 dp screen = 341.5 dp in the row's own coordinates
        // (the row image starts at the card's x = 16).
        val centre = accentCentreDp(settingsTag("appearance:0"), 91, 67, 232)
        assertTrue("checkmark centre $centre dp, expected 341.5 ± 1", abs(centre - 341.5f) <= 1f)
        assertEquals("row 1 contains no accent pixels", 0, accentCount(settingsTag("appearance:1"), 91, 67, 232))
        assertEquals("row 2 contains no accent pixels", 0, accentCount(settingsTag("appearance:2"), 91, 67, 232))
    }

    @Test
    fun theCheckmarkSamplesTheDarkAccentInDark() {
        mount(appearance = Appearance.Dark, current = Appearance.System)
        val centre = accentCentreDp(settingsTag("appearance:0"), 139, 124, 246)
        assertTrue("dark checkmark centre $centre dp, expected 341.5 ± 1", abs(centre - 341.5f) <= 1f)
        assertEquals("row 1 contains no accent pixels in dark", 0, accentCount(settingsTag("appearance:1"), 139, 124, 246))
        assertEquals("row 2 contains no accent pixels in dark", 0, accentCount(settingsTag("appearance:2"), 139, 124, 246))
    }

    // --------------------------------------------------------- behaviour

    @Test
    fun tappingADifferentRowMovesTheCheckmarkOnTheVeryNextFrame() {
        compose.mainClock.autoAdvance = false
        val picks = mutableListOf<Appearance>()
        val current = mutableStateOf(Appearance.System)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                AppearanceRowsColumn(
                    current = current.value,
                    onPick = { a -> picks += a; current.value = a },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                )
            }
        }
        compose.waitForIdle()
        row(settingsTag("appearance:2")).performClick()
        assertEquals("onPick fired once, synchronously", listOf(Appearance.Dark), picks)

        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeByFrame() // exactly one frame: no intermediate state

        row(settingsTag("appearance:2")).assert(selected)
        assertFalse(
            "row 0 is no longer selected",
            SemanticsProperties.Selected in row(settingsTag("appearance:0")).fetchSemanticsNode().config,
        )
        // The checkmark already sits in row 2 — no movement, no fade.
        val centre = accentCentreDp(settingsTag("appearance:2"), 91, 67, 232)
        assertTrue("checkmark centre $centre dp, expected 341.5 ± 1", abs(centre - 341.5f) <= 1f)
        assertEquals("row 0 has no accent pixels", 0, accentCount(settingsTag("appearance:0"), 91, 67, 232))
        assertEquals("row 1 has no accent pixels", 0, accentCount(settingsTag("appearance:1"), 91, 67, 232))
    }

    @Test
    fun tappingTheCheckedRowIsAVisualNoOp() {
        compose.mainClock.autoAdvance = false
        val picks = mutableListOf<Appearance>()
        val current = mutableStateOf(Appearance.System)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                AppearanceRowsColumn(
                    current = current.value,
                    onPick = { a -> picks += a; current.value = a },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                )
            }
        }
        compose.waitForIdle()
        // iOS runs the same handler and rewrites the same value; here
        // Prefs.setAppearance guards equality, so nothing changes.
        row(settingsTag("appearance:0")).performClick()
        assertEquals(listOf(Appearance.System), picks)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        row(settingsTag("appearance:0")).assert(selected)
        assertEquals("the checkmark never left row 0", 0, accentCount(settingsTag("appearance:1"), 91, 67, 232))
        assertEquals(0, accentCount(settingsTag("appearance:2"), 91, 67, 232))
        assertTrue(accentCount(settingsTag("appearance:0"), 91, 67, 232) > 0)
    }

    @Test
    fun theLazySectionFollowsTheRememberedState() {
        // Ticket 103's shape: the state at screen level, the section
        // inside the LazyColumn, the flow driven from outside.
        val flow = MutableStateFlow(Appearance.System)
        val calls = mutableListOf<Appearance>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val state = rememberAppearanceSectionState(appearance = flow, onPick = { calls += it })
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                ) {
                    appearanceSection(state)
                }
            }
        }
        compose.waitForIdle()
        row(settingsTag("appearance:0")).assertExists()
        row(settingsTag("appearance:1")).assertExists()
        row(settingsTag("appearance:2")).assertExists()
        row(settingsTag("appearance:1")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Appearance.Light), calls)

        // An external flip (the tap above did not write the flow): the
        // checkmark moves with no user input.
        compose.runOnUiThread { flow.value = Appearance.Dark }
        compose.waitForIdle()
        row(settingsTag("appearance:2")).assert(selected)
        assertFalse(
            "row 0 is no longer selected",
            SemanticsProperties.Selected in row(settingsTag("appearance:0")).fetchSemanticsNode().config,
        )
    }
}
