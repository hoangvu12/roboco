package sh.roboco.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Prefs
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.toggleTag
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack

/**
 * The Sessions Settings section (android-ui-parity #100) through the real
 * [sessionsSettingsSection] + [SessionsSettingsRowContent] + [openArchived]:
 *
 *  - the lazy keys (`grouped:sessions:*`) and their independence from the
 *    switch value, through a recording [LazyListScope] (the
 *    `GroupedListTest` method);
 *  - ticket 70's geometry at this section's call sites, on the ticket's
 *    `w402dp` phone at xxhdpi (1 dp = 3 px), font scale 1: the 57.8 dp
 *    header block, the 84/52 dp rows, the icon boxes at x 32…56 centred on
 *    their rows, the titles at x 72, the switch at x 370, the chevron box
 *    centre at 363.5, the separator under row 1 only, and the switch
 *    track's colours in both appearances;
 *  - behaviour: the switch tap/drag/TalkBack paths (exactly one
 *    `onGroupByProjectChange`), the non-clickable row body, the Archived
 *    tap with 70's `controlFill` flash, and the [openArchived] guard;
 *  - the Prefs integration (ticket 40) over a temp DataStore on the test
 *    scheduler: a tap flips and persists the preference, a reopen over the
 *    same file reads it back, and an external flip (the ⋯ menu stand-in)
 *    moves the switch with no user input and no switch haptic.
 *
 * The copy checks use the literal iOS copy, so a wrong string resource
 * fails the test. Written per the test policy; the batch/VPS run executes
 * it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h891dp-xxhdpi")
class SessionsSettingsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val feedback = RecordingFeedback()

    // ------------------------------------------------------------ helpers

    /**
     * Ticket 103's shape: the section in a 402 dp `LazyColumn` on the page
     * background, the strings resolved at screen level, the switch value a
     * getter read inside the row item.
     */
    private fun mount(
        appearance: Appearance = Appearance.Light,
        on: Boolean = false,
        onGroupByProjectChange: (Boolean) -> Unit = {},
        onOpenArchived: () -> Unit = {},
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    val strings = sessionsSettingsStrings()
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .background(RobocoTheme.palette.background),
                    ) {
                        sessionsSettingsSection(
                            strings = strings,
                            groupByProject = { on },
                            onGroupByProjectChange = onGroupByProjectChange,
                            onOpenArchived = onOpenArchived,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag)

    private fun switchNode() = compose.onNodeWithTag(toggleTag("groupByProject"), useUnmergedTree = true)

    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ±$tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun rowImage(tag: String): PixelMap = row(tag).captureToImage().toPixelMap()

    private fun switchImage(): PixelMap = switchNode().captureToImage().toPixelMap()

    /** Asserts the pixel is the given gray value in every channel. */
    private fun assertGray(px: PixelMap, x: Int, y: Int, expected: Int, tolerance: Int, what: String) {
        val c = px[x, y]
        val e = expected / 255f
        val t = tolerance / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${c.red}, ${c.green}, ${c.blue}), expected $expected ±$tolerance",
            abs(c.red - e) <= t && abs(c.green - e) <= t && abs(c.blue - e) <= t,
        )
    }

    /** Asserts the pixel is the given rgb value (± [tolerance], 8-bit steps). */
    private fun assertRgb(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, tolerance: Int, what: String) {
        val c = px[x, y]
        val t = tolerance / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).toInt()}, ${(c.green * 255f).toInt()}, ${(c.blue * 255f).toInt()}), " +
                "expected ($r, $g, $b) ±$tolerance",
            abs(c.red - r / 255f) <= t && abs(c.green - g / 255f) <= t && abs(c.blue - b / 255f) <= t,
        )
    }

    private fun channel(color: Color, ch: Int): Int = when (ch) {
        0 -> (color.red * 255f).toInt()
        1 -> (color.green * 255f).toInt()
        2 -> (color.blue * 255f).toInt()
        else -> error(ch)
    }

    private fun matches(c: Color, r: Int, g: Int, b: Int, tolerance: Int): Boolean =
        abs(channel(c, 0) - r) <= tolerance && abs(channel(c, 1) - g) <= tolerance && abs(channel(c, 2) - b) <= tolerance

    /** The ink centroid's x, over the rows [yRange] × [xRange], in image px. */
    private fun centroidX(px: PixelMap, yRange: IntRange, xRange: IntRange, r: Int, g: Int, b: Int, tolerance: Int): Float {
        var sum = 0f
        var count = 0
        for (y in yRange) {
            for (x in xRange) {
                if (matches(px[x, y], r, g, b, tolerance)) {
                    sum += x
                    count++
                }
            }
        }
        assertTrue("no matching pixels in x=$xRange, y=$yRange", count > 0)
        return sum / count
    }

    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()

        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) = Unit
    }

    // ---------------------------------------------------------- lazy keys

    /**
     * `LazyListScope` recorder, the `GroupedListTest` method: the builder
     * only stores the item lambdas, so the key/content-type surface is
     * testable without a layout.
     */
    private class RecordingLazyListScope : LazyListScope {
        val calls = mutableListOf<String>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            calls += "$key|$contentType"
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            for (i in 0 until count) {
                calls += "${key?.invoke(i)}|${contentType?.invoke(i)}"
            }
        }
    }

    @Test
    fun lazyKeysAreTheTicketsAndDoNotChangeWithTheValue() {
        val strings = SessionsSettingsStrings(
            header = "Sessions",
            groupByProject = "Group by Project",
            groupByProjectSubtitle = "Sessions outside your sections, under their project",
            archivedSessions = "Archived Sessions",
        )
        fun recorded(on: () -> Boolean): List<String> {
            val scope = RecordingLazyListScope()
            scope.sessionsSettingsSection(strings, on, { }, { })
            return scope.calls
        }
        val expected = listOf(
            "grouped:sessions:header|grouped-header",
            "grouped:sessions:row:groupByProject|grouped-row",
            "grouped:sessions:row:archived|grouped-row",
        )
        assertEquals(expected, recorded { false })
        assertEquals(
            "a flipped value keeps every key, so 98's thumb animation always completes",
            expected,
            recorded { true },
        )
    }

    // ---------------------------------------------------------- geometry

    @Test
    fun theSectionGeometryMatchesTheTicket() {
        mount()
        val row1 = row(settingsTag("groupByProject")).getUnclippedBoundsInRoot()
        val row2 = row(settingsTag("archived")).getUnclippedBoundsInRoot()
        assertDp(row1.left, 16.dp, "card left")
        assertDp(row1.right, 386.dp, "card right")
        // 15 + 20.8 + 2 × 16.9 + 15: the subtitle wraps to two lines at 402 dp.
        assertDp(heightOf(row1), 84.dp, "groupByProject row height (title + 2 subtitle lines)")
        // Title only, centred by the 52 dp minimum.
        assertDp(heightOf(row2), 52.dp, "archived row height")
        assertDp(row2.top - row1.bottom, 0.dp, "no gap between the rows", 0.5f)
        // The header block: 27 + 20.8 + 10 = 57.8, the card's top edge.
        assertDp(row1.top, 57.8.dp, "the header block above the card")

        val header = compose.onNodeWithText("Sessions", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(header.left, 32.dp, "header text left")
        assertDp(header.top, 27.dp, "header text top")
        assertDp(heightOf(header), 20.8.dp, "header line box")

        // The text column starts at 72 (16 + 16 + 24 + 16).
        assertDp(
            compose.onNodeWithText("Group by Project", useUnmergedTree = true).getUnclippedBoundsInRoot().left,
            72.dp,
            "row 1 title left",
        )
        assertDp(
            compose.onNodeWithText("Sessions outside your sections, under their project", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().left,
            72.dp,
            "row 1 subtitle left",
        )
        assertDp(
            compose.onNodeWithText("Archived Sessions", useUnmergedTree = true).getUnclippedBoundsInRoot().left,
            72.dp,
            "row 2 title left",
        )

        // The switch: 63 × 28, trailing edge 16 dp inside the card, vertically centred.
        val switch = switchNode().getUnclippedBoundsInRoot()
        assertDp(widthOf(switch), 63.dp, "switch width", 0.5f)
        assertDp(heightOf(switch), 28.dp, "switch height", 0.5f)
        assertDp(switch.right, 370.dp, "switch trailing edge")
        val rowCentre = (row1.top.value + row1.bottom.value) / 2f
        val switchCentre = (switch.top.value + switch.bottom.value) / 2f
        assertTrue(
            "switch centre $switchCentre should sit on the row centre $rowCentre (±1 dp)",
            abs(rowCentre - switchCentre) <= 1f,
        )
    }

    @Test
    fun theIconBoxesSpanX32To56CentredOnTheirRows() {
        mount()
        // Row 1 is 84 dp (252 px): the icon band is the row's centre ± 15 dp
        // (81..171); Row 2 is 52 dp (156 px): 40..115. The icon box spans
        // 16…40 dp into the card (image x 48…120 = screen x 32…56).
        val row1 = rowImage(settingsTag("groupByProject"))
        var icon = 0
        for (y in 81..171) {
            for (x in 48..120) {
                if (matches(row1[x, y], 98, 98, 106, 8)) icon++
            }
        }
        assertTrue("the square.stack.3d.up icon samples secondary", icon > 4)
        // The icon is centred on the 84 dp row (126 px), not aligned to the title.
        var sum = 0f
        var count = 0
        for (y in 81..171) {
            for (x in 48..120) {
                if (matches(row1[x, y], 98, 98, 106, 8)) {
                    sum += y
                    count++
                }
            }
        }
        assertTrue("no icon ink", count > 0)
        assertTrue("icon centroid y ${sum / count} px, expected ~126 (the row centre)", abs(sum / count - 126f) <= 4f)
        // The flanks stay clean: the box really spans 32…56, and the gap
        // between the icon box's end (56) and the title (72) is blank.
        for (x in 0..45) {
            for (y in 81..171) {
                assertTrue("stray ink at ($x, $y) before the icon box", row1[x, y].red > 0.6f)
            }
        }
        for (x in 128..165) {
            for (y in 81..171) {
                assertTrue("stray ink at ($x, $y) between the icon box and the title", row1[x, y].red > 0.6f)
            }
        }
        // Row 2's archivebox sits in the same box, centred on the 52 dp row.
        val row2 = rowImage(settingsTag("archived"))
        var archivedIcon = 0
        for (y in 40..115) {
            for (x in 48..120) {
                if (matches(row2[x, y], 98, 98, 106, 8)) archivedIcon++
            }
        }
        assertTrue("the archivebox icon samples secondary", archivedIcon > 4)
    }

    @Test
    fun theIconsSampleSecondaryInkInDark() {
        mount(appearance = Appearance.Dark)
        val row1 = rowImage(settingsTag("groupByProject"))
        var icon = 0
        for (y in 81..171) {
            for (x in 48..120) {
                if (matches(row1[x, y], 169, 169, 174, 8)) icon++
            }
        }
        assertTrue("dark: the square.stack.3d.up icon samples secondary", icon > 4)
        val row2 = rowImage(settingsTag("archived"))
        var archivedIcon = 0
        for (y in 40..115) {
            for (x in 48..120) {
                if (matches(row2[x, y], 169, 169, 174, 8)) archivedIcon++
            }
        }
        assertTrue("dark: the archivebox icon samples secondary", archivedIcon > 4)
    }

    @Test
    fun theArchivedChevronBoxCentreSitsAt3635() {
        mount()
        // UIKit tertiaryLabel over white ≈ (196, 196, 199). Box centre =
        // 370 − 19 − 7/2 = 347.5 (round 46: the chevron lays out at its 7 dp width; the centre is unchanged) dp from the card edge → 1042.5 image
        // px (± 1.5 dp), the GroupedListTest probe.
        val row2 = rowImage(settingsTag("archived"))
        val centroid = centroidX(row2, 40..115, 990..1080, 196, 196, 199, 14)
        assertTrue("chevron centroid $centroid px, expected ~1042.5 (363.5 dp from the screen)", abs(centroid - 1042.5f) <= 4.5f)
    }

    @Test
    fun aSeparatorRunsUnderRowOneOnly() {
        mount()
        // UIKit separator over white ≈ (198, 198, 200), on row 1's last
        // pixel row, 56 dp into the card to 16 dp before its edge — the
        // separator follows the text column, x 72…370. The last pixel row
        // is read from the capture, not hard-coded as 251: the row is
        // 84 dp only within theSectionGeometryMatchesTheTicket's ±1 dp —
        // 15 + 20.8 + 2 × 16.9 + 15 = 84.6 dp ≈ 254 px — so y = 251 is a
        // white pixel row above the hairline.
        val row1 = rowImage(settingsTag("groupByProject"))
        val y1 = row1.height - 1
        assertGray(row1, 170, y1, 198, 12, "separator start (56 dp into the card)")
        assertGray(row1, 160, y1, 255, 2, "left of the separator start")
        assertGray(row1, 1060, y1, 198, 12, "separator end (16 dp before the card edge)")
        assertGray(row1, 1070, y1, 255, 2, "right of the separator end")
        // No separator under the last row.
        val row2 = rowImage(settingsTag("archived"))
        assertGray(row2, 300, 155, 255, 2, "no separator under the last row")
        assertGray(row2, 1000, 155, 255, 2, "no separator under the last row (right)")
    }

    @Test
    fun theOnTrackSamplesTheLightAccent() {
        mount(on = true)
        val px = switchImage()
        // Accent #5B43E8 at the far (OFF) end of the track (8, 14) dp.
        assertRgb(px, 24, 42, 91, 67, 232, 3, "ON track at (8, 14) dp")
        // The white thumb at the trailing end, centre (42.5, 14) dp.
        assertRgb(px, 127, 42, 255, 255, 255, 2, "ON thumb centre")
    }

    @Test
    fun theOffTrackSamplesTheSystemGrey() {
        mount(on = false)
        val px = switchImage()
        // The system switch grey #C6C6CA (0x787880 @ 0.42 on white).
        assertRgb(px, 165, 42, 198, 198, 202, 3, "OFF track at (55, 14) dp")
        assertRgb(px, 61, 42, 255, 255, 255, 2, "OFF thumb centre")
    }

    @Test
    fun theDarkOnTrackSamplesTheDarkAccent() {
        mount(appearance = Appearance.Dark, on = true)
        val px = switchImage()
        assertRgb(px, 24, 42, 139, 124, 246, 3, "dark ON track at (8, 14) dp")
        assertRgb(px, 127, 42, 255, 255, 255, 2, "dark ON thumb centre")
    }

    // ------------------------------------------------------- copy + a11y

    @Test
    fun theCopyIsVerbatimFromTheIosReference() {
        mount()
        compose.onNodeWithText("Sessions").assertExists()
        compose.onNodeWithText("Group by Project").assertExists()
        compose.onNodeWithText("Sessions outside your sections, under their project").assertExists()
        compose.onNodeWithText("Archived Sessions").assertExists()
    }

    @Test
    fun theMergedToggleRowCarriesTheSwitchStateAndCopyWithoutARole() {
        mount(on = false)
        val node = row(settingsTag("groupByProject"))
        // The switch's ToggleableState merges up, so TalkBack reads the
        // title, the subtitle and "On"/"Off", and a double tap flips it.
        node.assertIsOff()
        val texts = node.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }
            ?: ""
        assertTrue("merged text should contain the title: '$texts'", texts.contains("Group by Project"))
        assertTrue("merged text should contain the subtitle: '$texts'", texts.contains("Sessions outside your sections"))
        assertNull("Compose does not merge Role upward: no Role.Button on the merged row", node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        // The switch's own node carries Role.Switch and the single toggle tag.
        assertEquals(Role.Switch, switchNode().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        assertEquals(
            "the toggle-groupByProject tag appears exactly once",
            1,
            compose.onAllNodesWithTag(toggleTag("groupByProject"), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theArchivedRowIsAButtonAndTheHeaderIsAHeading() {
        mount()
        val archived = row(settingsTag("archived"))
        assertEquals(Role.Button, archived.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        archived.assertHasClickAction()
        val headings = compose
            .onAllNodes(SemanticsMatcher("is a heading") { it.config.contains(SemanticsProperties.Heading) })
            .fetchSemanticsNodes()
        assertEquals("only the Sessions header is a heading", 1, headings.size)
    }

    // --------------------------------------------------------- behaviour

    @Test
    fun tappingTheSwitchWhenOffRequestsTrueExactlyOnce() {
        val calls = mutableListOf<Boolean>()
        mount(on = false, onGroupByProjectChange = { calls += it })
        switchNode().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), calls)
        // The switch's own feedback fired (inside RobocoSwitch, before the
        // callback); nothing else happens — no extra haptic.
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)
    }

    @Test
    fun tappingTheSwitchWhenOnRequestsFalseExactlyOnce() {
        val calls = mutableListOf<Boolean>()
        mount(on = true, onGroupByProjectChange = { calls += it })
        switchNode().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), calls)
        assertEquals(listOf(Haptic.ToggleOff), feedback.haptics)
    }

    @Test
    fun draggingTheThumbRightCommitsOnRelease() {
        val calls = mutableListOf<Boolean>()
        mount(on = false, onGroupByProjectChange = { calls += it })
        // 30 dp of travel: Robolectric's 16 dp touch slop is consumed by the
        // slop detector, leaving 14 dp against the 22 dp slide (fraction
        // ~0.64), so the release passes the halfway point and commits ON
        // (RobocoSwitchTest's precedent).
        switchNode().performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(10.dp.toPx(), 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(true), calls)
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)
    }

    @Test
    fun talkBacksDoubleTapOnTheMergedRowFlipsTheSwitch() {
        val calls = mutableListOf<Boolean>()
        mount(on = false, onGroupByProjectChange = { calls += it })
        row(settingsTag("groupByProject")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("the merged node exposes the switch's click action", listOf(true), calls)
        assertEquals(listOf(Haptic.ToggleOn), feedback.haptics)
    }

    @Test
    fun tappingTheToggleRowBodyDoesNothing() {
        val changes = mutableListOf<Boolean>()
        var opened = 0
        mount(onGroupByProjectChange = { changes += it }, onOpenArchived = { opened++ })

        // A raw touch on the title column (node-local 90 dp across, the
        // row's centre height — root x ≈ 106, inside the 72…295 text column,
        // far from the switch's 307…370 box): a row with no click action.
        row(settingsTag("groupByProject")).performTouchInput { click(Offset(90.dp.toPx(), 42.dp.toPx())) }
        compose.waitForIdle()
        assertTrue("the row body never flips the switch (iOS default: break)", changes.isEmpty())
        assertEquals(0, opened)

        // And no highlight: hold the touch down, the fill stays elevated.
        compose.mainClock.autoAdvance = false
        row(settingsTag("groupByProject")).performTouchInput { down(Offset(90.dp.toPx(), 42.dp.toPx())) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(200)
        assertGray(rowImage(settingsTag("groupByProject")), 24, 126, 255, 2, "no highlight while the row body is touched")
        row(settingsTag("groupByProject")).performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        assertGray(rowImage(settingsTag("groupByProject")), 24, 126, 255, 2, "no highlight after release")
    }

    @Test
    fun tappingArchivedCallsOpenArchivedExactlyOnce() {
        var opened = 0
        mount(onOpenArchived = { opened++ })
        row(settingsTag("archived")).performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
        assertTrue("no haptic on a Settings row tap", feedback.haptics.isEmpty())
    }

    @Test
    fun theArchivedPressShowsTheControlFillHighlight() {
        // A plain Column (no scroll container) so Compose does not delay the
        // press by the tap timeout, the GroupedListTest method.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    Column(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        SessionsSettingsRowContent(
                            row = SessionsSettingsRow.Archived,
                            position = GroupedPosition.Single,
                            strings = sessionsSettingsStrings(),
                            groupByProject = false,
                            onGroupByProjectChange = { },
                            onOpenArchived = { },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val center = row(settingsTag("archived")).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        // controlFill over elevated = #EFEFEF (239, 239, 239).
        assertGray(rowImage(settingsTag("archived")), 24, 78, 239, 2, "pressed fill")

        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(260)
        assertGray(rowImage(settingsTag("archived")), 24, 78, 255, 2, "faded back to elevated")
    }

    // --------------------------------------------------------- the push

    /** The Settings tab's own root: the pushed screen goes on top of it. */
    private data class SettingsRootRoute(override val paneTitle: String = "Settings") : NavRoute

    @Test
    fun openArchivedPushesTheArchivedFolderOnTopOfTheStack() {
        val root = SettingsRootRoute()
        val nav = NavStack(root)
        openArchived(nav)
        assertEquals(FolderRoute("archived", "Archived"), nav.top.route)
        assertEquals(2, nav.entries.size)
        assertSame("the root is unchanged: no tab switch, no pop", root, nav.entries.first().route)
    }

    @Test
    fun aSecondCallWhileArchivedIsOnTopIsIgnored() {
        val nav = NavStack(SettingsRootRoute())
        openArchived(nav)
        openArchived(nav)
        assertEquals("the guard swallows the second tap", 2, nav.entries.size)
        assertEquals(FolderRoute("archived", "Archived"), nav.top.route)
    }

    // -------------------------------------------------- the Prefs wiring

    /** Ticket 40's recipe: one live Prefs at a time over a temp file. */
    private fun TestScope.newPrefs(fileName: String): Pair<Prefs, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tmpFolder.root, "$fileName.preferences_pb") },
        )
        return Prefs(store, scope, io = StandardTestDispatcher(testScheduler)) to scope
    }

    private fun mountPrefs(prefs: Prefs) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    val group by prefs.groupByProject.collectAsState()
                    val strings = sessionsSettingsStrings()
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .background(RobocoTheme.palette.background),
                    ) {
                        sessionsSettingsSection(
                            strings = strings,
                            groupByProject = { group },
                            onGroupByProjectChange = prefs::setGroupByProject,
                            onOpenArchived = { },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun flippingTheSwitchFlipsAndPersistsThePreference() = runTest {
        val (prefs, scope) = newPrefs("switch")
        prefs.awaitLoaded()
        assertEquals(false, prefs.groupByProject.value)

        mountPrefs(prefs)
        switchNode().assertIsOff()
        switchNode().performClick()
        compose.waitForIdle()
        assertEquals("the StateFlow flips at once", true, prefs.groupByProject.value)
        switchNode().assertIsOn()

        // Persisted: cancel the first DataStore's scope (DataStore forbids
        // two live instances on one file), reopen over the same file.
        advanceUntilIdle()
        scope.cancel()
        scope.coroutineContext.job.join()
        val (reopened, reopenedScope) = newPrefs("switch")
        reopened.awaitLoaded()
        assertEquals("a fresh Prefs reads the stored value", true, reopened.groupByProject.value)
        reopenedScope.cancel()
        reopenedScope.coroutineContext.job.join()
    }

    @Test
    fun anExternalFlipMovesTheSwitchWithNoUserInput() = runTest {
        val (prefs, scope) = newPrefs("external")
        prefs.awaitLoaded()
        mountPrefs(prefs)
        switchNode().assertIsOff()

        // The ⋯ menu stand-in: the value changes from outside composition.
        compose.runOnUiThread { prefs.setGroupByProject(true) }
        compose.waitForIdle()
        switchNode().assertIsOn()

        compose.runOnUiThread { prefs.setGroupByProject(false) }
        compose.waitForIdle()
        switchNode().assertIsOff()

        assertTrue("a flip that arrives from the caller plays no switch haptic", feedback.haptics.isEmpty())
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
