package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face

/**
 * The inset-grouped list (android-ui-parity #70): positions, the card
 * shape, the layout geometry, the pixels of the separator/accessories and
 * the pressed highlight's timing — all on the ticket's `w402dp` device at
 * xxhdpi (1 dp = 3 px), font scale 1.
 *
 * Shape coverage rasterises `createOutline` into an `android.graphics.Region`
 * (`Region.setPath`) — the exact point hit tests of the acceptance list —
 * over a 370 × 52 dp card rect (1110 × 156 px, r = 78 px).
 *
 * The pressed-highlight tests host the row in a **plain `Column`** (no
 * scroll container) so Compose does not delay the press by the tap timeout,
 * and freeze the clock (`autoAdvance = false`), advancing single frames —
 * the `ScrollEdgeEffectTest` method.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h891dp-xxhdpi")
class GroupedListTest {

    @get:Rule
    val compose = createComposeRule()

    /** The two-line subtitle of the ticket's geometry table. */
    private val twoLineSubtitle = "Sessions outside your sections, under their project"

    /** Long enough to hit the value accessory's 185 dp (50 %) cap. */
    private val longValue = "0.1.0 (12345) a deliberately long build string that must ellipsize"

    // ------------------------------------------------------------ helpers

    private fun host(appearance: Appearance = Appearance.Light, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) {
                // Opaque page background behind the card: the rounded corners
                // of the row's own clip are transparent, so pixel probes
                // outside the card must not read the (uncertain) window
                // background.
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) { content() }
            }
        }
        compose.waitForIdle()
    }

    /** The node's bounds in dp (± the ticket's 1 dp unless said otherwise). */
    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ± $tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    /** [DpRect] carries no width/height of its own in this Compose. */
    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun rowImage(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    /** Asserts the pixel is the given gray value in every channel. */
    private fun assertGray(px: PixelMap, x: Int, y: Int, expected: Int, tolerance: Int, what: String) {
        val c = px[x, y]
        val e = expected / 255f
        val t = tolerance / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${c.red}, ${c.green}, ${c.blue}), expected $expected ± $tolerance",
            abs(c.red - e) <= t && abs(c.green - e) <= t && abs(c.blue - e) <= t,
        )
    }

    private fun isChannel(c: Color, channel: (Color) -> Float, expected: Int, tolerance: Int): Boolean =
        abs(channel(c) - expected / 255f) <= tolerance / 255f

    /** The ink centroid's x, over the rows [yRange] × [xRange], in image px. */
    private fun centroidX(
        px: PixelMap,
        yRange: IntRange,
        xRange: IntRange,
        match: (Color) -> Boolean,
    ): Float {
        var sum = 0f
        var count = 0
        for (y in yRange) {
            for (x in xRange) {
                if (match(px[x, y])) {
                    sum += x
                    count++
                }
            }
        }
        assertTrue("no matching pixels in x=$xRange, y=$yRange", count > 0)
        return sum / count
    }

    // ------------------------------------------------------ position logic

    @Test
    fun positionOfMapsIndexAndCount() {
        assertEquals(GroupedPosition.Single, GroupedPosition.of(0, 1))
        assertEquals(GroupedPosition.First, GroupedPosition.of(0, 3))
        assertEquals(GroupedPosition.Middle, GroupedPosition.of(1, 3))
        assertEquals(GroupedPosition.Last, GroupedPosition.of(2, 3))
        assertEquals(GroupedPosition.Middle, GroupedPosition.of(2, 9))
        assertEquals(GroupedPosition.Last, GroupedPosition.of(8, 9))
    }

    @Test
    fun onlyFirstAndMiddleCarryASeparator() {
        val expected = mapOf(
            GroupedPosition.Single to false,
            GroupedPosition.First to true,
            GroupedPosition.Middle to true,
            GroupedPosition.Last to false,
        )
        for ((position, has) in expected) {
            assertEquals("hasSeparator($position)", has, position.hasSeparator)
        }
    }

    @Test
    fun positionOfRejectsEmptyCardsAndOutOfRangeIndices() {
        assertThrows(IllegalArgumentException::class.java) { GroupedPosition.of(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { GroupedPosition.of(-1, 3) }
        assertThrows(IllegalArgumentException::class.java) { GroupedPosition.of(3, 3) }
    }

    // -------------------------------------------------- the lazy emitter

    /**
     * Records every item the DSL emits as "key|contentType" strings. Never
     * composes: the content lambdas are stored, not invoked, so the key and
     * content-type surface is testable without a layout.
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
    fun groupedSectionEmitsKeysAndContentTypes() {
        val scope = RecordingLazyListScope()
        scope.groupedSection(
            key = "s",
            header = "Appearance",
            items = listOf("a", "b", "c"),
            itemKey = { it },
            footer = "F",
            itemContent = { _, _ -> },
        )
        assertEquals(
            listOf(
                "grouped:s:header|grouped-header",
                "grouped:s:row:a|grouped-row",
                "grouped:s:row:b|grouped-row",
                "grouped:s:row:c|grouped-row",
                "grouped:s:footer|grouped-footer",
            ),
            scope.calls,
        )
    }

    @Test
    fun groupedSectionEmitsTheHeaderForAnEmptyListAndNoHeaderForNull() {
        val empty = RecordingLazyListScope()
        empty.groupedSection(key = "e", header = "E", items = emptyList<String>(), itemKey = { it }, itemContent = { _, _ -> })
        assertEquals(listOf("grouped:e:header|grouped-header"), empty.calls)

        val noHeader = RecordingLazyListScope()
        noHeader.groupedSection(key = "n", header = null, items = listOf("x"), itemKey = { it }, itemContent = { _, _ -> })
        assertEquals(listOf("grouped:n:row:x|grouped-row"), noHeader.calls)
    }

    // ------------------------------------------------------------ shape

    private val shapeDensity = Density(3f) // xxhdpi
    private val cardWidth = 370.dp * 3 // the card rect: 370 × 52 dp
    private val cardHeight = 52.dp * 3

    private fun outline(position: GroupedPosition): Outline =
        GroupedCardShape(position).createOutline(Size(cardWidth.value, cardHeight.value), LayoutDirection.Ltr, shapeDensity)

    /**
     * The outline's region for exact hit tests: the Compose path's backing
     * `android.graphics.Path` ([asAndroidPath]) rasterised into a
     * [android.graphics.Region] clipped to the card rect.
     * `Outline.Rectangle` is the whole rect.
     *
     * Not `Path.iterator()`: Compose's `PathIterator` loads the
     * `androidx.graphics.path` native library, which Robolectric cannot
     * load (UnsatisfiedLinkError); `Region.setPath` runs on NATIVE graphics.
     */
    private fun region(position: GroupedPosition): android.graphics.Region {
        val w = cardWidth.value.toInt()
        val h = cardHeight.value.toInt()
        val out = outline(position)
        return when (out) {
            is Outline.Rectangle -> android.graphics.Region(0, 0, w, h)
            is Outline.Rounded -> android.graphics.Region(0, 0, w, h) // never produced by this shape
            is Outline.Generic -> {
                val path = out.path.asAndroidPath()
                android.graphics.Region().apply { setPath(path, android.graphics.Region(0, 0, w, h)) }
            }
        }
    }

    @Test
    fun singleRowShapeIsACapsule() {
        val out = outline(GroupedPosition.Single)
        assertTrue("Single should be a generic path outline, got $out", out is Outline.Generic)
        // The path covers the whole rect (r = 26 dp = 78 px = h/2 → the clamp
        // short-circuits to the exact capsule, tangent to all four edges).
        val bounds = (out as Outline.Generic).path.getBounds()
        assertTrue("capsule bounds $bounds", abs(bounds.left) < 1f && abs(bounds.top) < 1f)
        assertTrue("capsule bounds $bounds", abs(bounds.right - 1110f) < 2f && abs(bounds.bottom - 156f) < 2f)
        val region = region(GroupedPosition.Single)
        // Inside: the centre, the middle of the left/right caps (4 px in),
        // the flat top edge's middle, and a 45°-ish interior point.
        for (p in listOf(Offset(555f, 78f), Offset(4f, 78f), Offset(1106f, 78f), Offset(555f, 2f), Offset(78f, 78f), Offset(30f, 30f))) {
            assertTrue("capsule should contain $p", region.contains(p.x.toInt(), p.y.toInt()))
        }
        // Outside: all four corners (a smaller or unclamped radius would
        // still exclude them, but a *square* card — the bug class — would not).
        for (p in listOf(Offset(2f, 2f), Offset(1108f, 2f), Offset(2f, 154f), Offset(1108f, 154f), Offset(16f, 16f))) {
            assertTrue("capsule should exclude $p", !region.contains(p.x.toInt(), p.y.toInt()))
        }
    }

    @Test
    fun firstRowShapeRoundsTheTopAndKeepsTheBottomSquare() {
        val region = region(GroupedPosition.First)
        // Square bottom: the bottom corners, 2 px in, belong to the card.
        assertTrue("First should contain the bottom-left corner", region.contains(2, 154))
        assertTrue("First should contain the bottom-right corner", region.contains(1108, 154))
        // Rounded top: the top-left/top-right corner pixels are outside, and
        // so is a 45°-ish point 16 px in (the continuous corner's curve).
        assertTrue("First should exclude the top-left corner", !region.contains(2, 2))
        assertTrue("First should exclude the top-right corner", !region.contains(1108, 2))
        assertTrue("First should exclude (16,16)", !region.contains(16, 16))
        // The flat top edge between the corners and the middle of the card.
        assertTrue(region.contains(555, 2))
        assertTrue(region.contains(555, 78))
    }

    @Test
    fun lastRowShapeMirrorsFirst() {
        val region = region(GroupedPosition.Last)
        assertTrue("Last should contain the top-left corner", region.contains(2, 2))
        assertTrue("Last should contain the top-right corner", region.contains(1108, 2))
        assertTrue("Last should exclude the bottom-left corner", !region.contains(2, 154))
        assertTrue("Last should exclude the bottom-right corner", !region.contains(1108, 154))
        assertTrue("Last should exclude (16,140)", !region.contains(16, 140))
        assertTrue(region.contains(555, 154))
        assertTrue(region.contains(555, 78))
    }

    @Test
    fun middleRowShapeIsAPlainRectangle() {
        assertTrue(outline(GroupedPosition.Middle) is Outline.Rectangle)
        val region = region(GroupedPosition.Middle)
        assertTrue(region.contains(2, 2))
        assertTrue(region.contains(1108, 154))
    }

    @Test
    fun degenerateSizesFallBackToRectangles() {
        for (position in GroupedPosition.entries) {
            val out = GroupedCardShape(position).createOutline(Size(0f, 156f), LayoutDirection.Ltr, shapeDensity)
            assertTrue("0-width $position should be a rectangle, got $out", out is Outline.Rectangle)
        }
    }

    @Test
    fun shapeEqualityFollowsPositionAndRadius() {
        assertEquals(GroupedCardShape(GroupedPosition.First), GroupedCardShape(GroupedPosition.First))
        assertEquals(
            GroupedCardShape(GroupedPosition.First).hashCode(),
            GroupedCardShape(GroupedPosition.First).hashCode(),
        )
        assertTrue(GroupedCardShape(GroupedPosition.First) != GroupedCardShape(GroupedPosition.Last))
        assertTrue(GroupedCardShape(GroupedPosition.First, 20.dp) != GroupedCardShape(GroupedPosition.First))
    }

    // ------------------------------------------------------- geometry

    @Test
    fun rowHeightsMatchThePaddingModel() {
        host {
            Column {
                GroupedRow("Title", GroupedPosition.Single, testTag = "t0", onClick = null)
                GroupedRow("Title", GroupedPosition.Single, subtitle = "One line", testTag = "t1", onClick = null)
                // The ticket's "Group by Project" row carries its leading
                // symbol: the text starts at 56 dp, leaving a ~272 dp
                // column (with the disclosure) for the 305 dp Geist-13
                // subtitle, so it wraps to two lines. Without the icon the
                // column is ~312 dp and the subtitle fits on one line.
                GroupedRow(
                    "Title",
                    GroupedPosition.Single,
                    subtitle = twoLineSubtitle,
                    icon = RobocoIcons.SquareStack3dUp,
                    testTag = "t2",
                    onClick = null,
                )
            }
        }
        // 30 + 20.8 = 50.8 → 52 by the minimum; + 16.9; + 2 × 16.9.
        for ((tag, height) in listOf("t0" to 52.dp, "t1" to 68.dp, "t2" to 84.dp)) {
            assertDp(heightOf(compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()), height, "row height $tag")
        }
    }

    @Test
    fun cardTitleHeaderAndValueGeometry() {
        host {
            Column {
                GroupedHeader("Appearance")
                GroupedRow(
                    "System",
                    GroupedPosition.First,
                    icon = RobocoIcons.CircleLeftHalfFilled,
                    accessories = listOf(GroupedAccessory.Check(on = true)),
                    testTag = "r0",
                    onClick = null,
                )
                GroupedRow("Light", GroupedPosition.Middle, icon = RobocoIcons.SunMax, testTag = "r1", onClick = null)
                GroupedRow("Dark", GroupedPosition.Last, icon = RobocoIcons.Moon, testTag = "r2", onClick = null)
                GroupedRow(
                    "Version",
                    GroupedPosition.Single,
                    accessories = listOf(GroupedAccessory.Value(longValue)),
                    testTag = "r3",
                    onClick = null,
                )
                GroupedHeader(" ")
                GroupedRow("Remove Wallpaper", GroupedPosition.Single, testTag = "r4", onClick = null)
            }
        }
        val row = compose.onNodeWithTag("r0").getUnclippedBoundsInRoot()
        assertDp(row.left, 16.dp, "card left")
        assertDp(row.right, 386.dp, "card right")
        assertDp(widthOf(row), 370.dp, "card width")
        assertDp(heightOf(row), 52.dp, "row height")
        assertDp(row.top, 57.8.dp, "first row top = the header block (27 + 20.8 + 10)")

        // The title starts 56 dp inside the card (72 from the screen).
        val title = compose.onNodeWithText("System", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(title.left, 72.dp, "title left")

        // The header: text 32 from the screen, its line box 20.8, the block
        // 57.8 (= the first row's top).
        val header = compose.onNodeWithText("Appearance", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(header.left, 32.dp, "header text left")
        assertDp(header.top, 27.dp, "header text top")
        assertDp(heightOf(header), 20.8.dp, "header line box")

        // The value accessory's box ends 16 dp before the card edge (the
        // long string is capped at 50 % = 185 dp, so the node reports the
        // full box, not the ink).
        val value = compose.onNodeWithText(longValue, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(value.right, 370.dp, "value text right")
        assertDp(widthOf(value), 185.dp, "value text max width")

        // The blank header keeps the block's height (no text, no semantics).
        val r3 = compose.onNodeWithTag("r3").getUnclippedBoundsInRoot()
        val r4 = compose.onNodeWithTag("r4").getUnclippedBoundsInRoot()
        assertDp(r4.top - r3.bottom, 57.8.dp, "blank header block height")
    }

    @Test
    fun separatorPixelsRunWithTheTextColumnAndEnd16BeforeTheCard() {
        host {
            Column {
                GroupedRow("A", GroupedPosition.First, icon = RobocoIcons.Plus, testTag = "a", onClick = null)
                GroupedRow("B", GroupedPosition.Middle, icon = RobocoIcons.Plus, testTag = "b", onClick = null)
                GroupedRow("C", GroupedPosition.Last, icon = RobocoIcons.Plus, testTag = "c", onClick = null)
                GroupedRow("D", GroupedPosition.First, testTag = "d", onClick = null)
                GroupedRow("E", GroupedPosition.Last, testTag = "e", onClick = null)
            }
        }
        // Image-local px (the image starts at the card's left edge; 1 dp = 3
        // px). The separator sits on the row's last pixel row (y = 155).
        val first = rowImage("a")
        // UIKit separator over white: #C6C6C8 ≈ (198, 198, 200).
        assertGray(first, 170, 155, 198, 12, "separator start (56 dp in the card)")
        assertGray(first, 160, 155, 255, 2, "left of the separator start")
        assertGray(first, 1060, 155, 198, 12, "separator end (16 dp before the card edge)")
        assertGray(first, 1070, 155, 255, 2, "right of the separator end")
        // A row without an icon starts its separator at ContentStart (16 dp
        // inside the card = screen x 32).
        val noIcon = rowImage("d")
        assertGray(noIcon, 50, 155, 198, 12, "no-icon separator start (16 dp in the card)")
        assertGray(noIcon, 40, 155, 255, 2, "left of the no-icon separator start")
        // No separator after the last row of a card, nor on a single row.
        for (tag in listOf("c", "e")) {
            val px = rowImage(tag)
            assertGray(px, 300, 155, 255, 2, "no separator under $tag")
            assertGray(px, 1000, 155, 255, 2, "no separator under $tag (right)")
        }
    }

    @Test
    fun accessoryInkCentresMatchTheMeasurements() {
        host {
            Column {
                GroupedRow("Pair an engine", GroupedPosition.Single, icon = RobocoIcons.Plus, testTag = "chev", onClick = null)
                GroupedRow("Light", GroupedPosition.Single, accessories = listOf(GroupedAccessory.Check(on = true)), testTag = "check", onClick = null)
                GroupedRow("Online", GroupedPosition.Single, accessories = listOf(GroupedAccessory.Dot(online = true)), testTag = "dot", onClick = null)
            }
        }
        // The disclosure chevron: UIKit tertiaryLabel over white ≈
        // (196, 196, 199). Box centre = 370 − 19 − 7/2 = 347.5 (round 46: the chevron lays out at its 7 dp width; the centre is unchanged) dp from
        // the card edge → 1042.5 image px (± 1.5 dp).
        val chev = rowImage("chev")
        val chevronInk = centroidX(chev, 40..115, 990..1080) { c ->
            isChannel(c, { it.red }, 196, 14) && isChannel(c, { it.green }, 196, 14) && isChannel(c, { it.blue }, 199, 14)
        }
        assertTrue("chevron centroid $chevronInk px, expected ~1042.5", abs(chevronInk - 1042.5f) <= 4.5f)

        // The checkmark: accent #5B43E8 = (91, 67, 232). Ink centre =
        // 370 − 18.3 − 20.4/2 = 341.5 dp → 1024.5 px.
        val check = rowImage("check")
        val checkInk = centroidX(check, 40..115, 980..1070) { c ->
            isChannel(c, { it.red }, 91, 20) && isChannel(c, { it.green }, 67, 20) && isChannel(c, { it.blue }, 232, 20)
        }
        assertTrue("checkmark centroid $checkInk px, expected ~1024.5", abs(checkInk - 1024.5f) <= 4.5f)

        // The dot: success #15803D = (21, 128, 61), 8 dp wide ending 16 dp
        // before the card edge → right edge at 354 dp = 1062 px.
        val dot = rowImage("dot")
        var rightmost = -1
        outer@ for (x in dot.width - 1 downTo 1000) {
            for (y in 40..115) {
                val c = dot[x, y]
                if (isChannel(c, { it.red }, 21, 25) && isChannel(c, { it.green }, 128, 25) && isChannel(c, { it.blue }, 61, 25)) {
                    rightmost = x
                    break@outer
                }
            }
        }
        assertTrue("no dot pixels found", rightmost >= 0)
        assertTrue("dot right edge $rightmost px, expected ~1062", abs(rightmost - 1062f) <= 4.5f)

        // The leading icon's ink starts at the 24 dp box (32 screen):
        // nothing darker than the separator before image x = 46 (15.3 dp),
        // and the gap between the icon box's end (56 screen = 120 px) and
        // the title (72 screen = 168 px) is blank too — so the box really
        // spans 32…56 screen.
        for (x in 0..45) {
            for (y in 40..115) {
                val c = chev[x, y]
                assertTrue("stray ink at ($x, $y) before the icon box: $c", c.red > 0.6f)
            }
        }
        for (x in 128..165) {
            for (y in 40..115) {
                val c = chev[x, y]
                assertTrue("stray ink at ($x, $y) between the icon box and the title: $c", c.red > 0.6f)
            }
        }
    }

    @Test
    fun checkFalseRendersNothingAndReservesNothing() {
        host {
            Column {
                GroupedRow(
                    "Title",
                    GroupedPosition.Single,
                    subtitle = twoLineSubtitle,
                    accessories = listOf(GroupedAccessory.Check(on = false)),
                    testTag = "off",
                    onClick = null,
                )
                GroupedRow(
                    "Title",
                    GroupedPosition.Single,
                    subtitle = twoLineSubtitle,
                    accessories = emptyList(),
                    testTag = "none",
                    onClick = null,
                )
            }
        }
        // Same content, same layout: an off checkmark reserves no space at
        // all — its row measures exactly like a row with no accessories.
        val off = compose.onNodeWithTag("off").getUnclippedBoundsInRoot()
        val none = compose.onNodeWithTag("none").getUnclippedBoundsInRoot()
        assertEquals("Check(false) must not reserve space", heightOf(none).value, heightOf(off).value, 0.05f)
        // And nothing is drawn: no accent ink anywhere in the off row.
        val px = rowImage("off")
        var accent = 0
        for (x in 0 until px.width) {
            for (y in 0 until px.height) {
                val c = px[x, y]
                if (isChannel(c, { it.red }, 91, 20) && isChannel(c, { it.green }, 67, 20) && isChannel(c, { it.blue }, 232, 20)) {
                    accent++
                }
            }
        }
        assertEquals("Check(false) must draw no checkmark pixels", 0, accent)
    }

    @Test
    fun destructiveRowInksTitleAndIconInDanger() {
        // One setContent per test (a second one throws "has already set
        // content"): the appearance flips through state instead.
        var current by mutableStateOf(Appearance.Light)
        compose.setContent {
            RobocoTheme(current) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Column {
                        GroupedRow(
                            "Remove Wallpaper",
                            GroupedPosition.Single,
                            icon = RobocoIcons.Trash,
                            destructive = true,
                            accessories = emptyList(),
                            testTag = "danger",
                            onClick = null,
                        )
                    }
                }
            }
        }
        for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
            current = appearance
            compose.waitForIdle()
            val px = rowImage("danger")
            // danger: #DC2626 light, #F87171 dark.
            val expected = if (appearance == Appearance.Dark) Triple(248, 113, 113) else Triple(220, 38, 38)
            var danger = 0
            for (x in 0 until px.width) {
                for (y in 0 until px.height) {
                    val c = px[x, y]
                    if (isChannel(c, { it.red }, expected.first, 8) &&
                        isChannel(c, { it.green }, expected.second, 8) &&
                        isChannel(c, { it.blue }, expected.third, 8)
                    ) {
                        danger++
                    }
                }
            }
            assertTrue("${appearance.name}: expected the icon and title glyphs in danger red, found $danger px", danger >= 30)
        }
    }

    // ------------------------------------------------- pressed highlight

    /** The pixel at (row x + 8, row centre), the ticket's probe point. */
    private fun probePixel(tag: String): Color {
        val px = rowImage(tag)
        return px[24, px.height / 2]
    }

    @Test
    fun pressedHighlightAppearsInstantlyAndFadesOutOver250ms() {
        compose.mainClock.autoAdvance = false
        host { Column { GroupedRow("System", GroupedPosition.Single, icon = RobocoIcons.CircleLeftHalfFilled, testTag = "row", onClick = { }) } }
        val center = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        // controlFill over elevated = #EFEFEF (239, 239, 239).
        assertGray(rowImage("row"), 24, 78, 239, 2, "pressed fill")

        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(125)
        // Ease-in-out at 50 %: alpha 0.5 → ≈ 247, between #EFEFEF and white.
        val mid = probePixel("row")
        assertTrue(
            "mid-fade rgb(${mid.red}, ${mid.green}, ${mid.blue}) should sit between 239 and 255",
            mid.red in (240f / 255f)..(254f / 255f) && mid.green in (240f / 255f)..(254f / 255f) &&
                mid.blue in (240f / 255f)..(254f / 255f),
        )

        compose.mainClock.advanceTimeBy(135) // 260 ms past the release
        assertGray(rowImage("row"), 24, 78, 255, 2, "fully faded highlight")
    }

    @Test
    fun pressedHighlightInDarkMode() {
        compose.mainClock.autoAdvance = false
        host(Appearance.Dark) { Column { GroupedRow("System", GroupedPosition.Single, testTag = "row", onClick = { }) } }
        val center = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        // controlFill over elevated dark = #212123 (33, 33, 35).
        val c = probePixel("row")
        assertTrue(
            "dark pressed rgb(${c.red}, ${c.green}, ${c.blue})",
            abs(c.red - 33f / 255f) <= 2f / 255f && abs(c.green - 33f / 255f) <= 2f / 255f &&
                abs(c.blue - 35f / 255f) <= 2f / 255f,
        )
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        val faded = probePixel("row")
        assertTrue(
            "dark faded rgb(${faded.red}, ${faded.green}, ${faded.blue}) should be elevated #111113",
            abs(faded.red - 17f / 255f) <= 2f / 255f && abs(faded.green - 17f / 255f) <= 2f / 255f &&
                abs(faded.blue - 19f / 255f) <= 2f / 255f,
        )
    }

    @Test
    fun aQuickTapStillFlashesBeforeFading() {
        compose.mainClock.autoAdvance = false
        host { Column { GroupedRow("System", GroupedPosition.Single, testTag = "row", onClick = { }) } }
        val center = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.center
        // Press and release in the same frame: the interaction stream sees
        // both, so the highlight still snaps to 1 before fading.
        compose.onRoot().performTouchInput { down(center); up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val flash = probePixel("row")
        assertTrue(
            "quick-tap flash rgb(${flash.red}, ${flash.green}, ${flash.blue}) should be visibly grey",
            flash.red < 253f / 255f && flash.green < 253f / 255f && flash.blue < 253f / 255f,
        )
        compose.mainClock.advanceTimeBy(260)
        assertGray(rowImage("row"), 24, 78, 255, 2, "quick-tap fade completed")
    }

    @Test
    fun rowsWithoutOnClickNeverHighlight() {
        compose.mainClock.autoAdvance = false
        host { Column { GroupedRow("System", GroupedPosition.Single, testTag = "row", onClick = null) } }
        val center = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertGray(rowImage("row"), 24, 78, 255, 2, "idle fill with a touch down")
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        assertGray(rowImage("row"), 24, 78, 255, 2, "idle fill after release")
    }

    // ------------------------------------------------------- semantics

    @Test
    fun onClickFiresOncePerTapAndTheRowIsASelectedButton() {
        var fired = 0
        host {
            Column {
                GroupedRow(
                    "System",
                    GroupedPosition.Single,
                    accessories = listOf(GroupedAccessory.Check(on = true)),
                    testTag = settingsTag("system"),
                    onClick = { fired++ },
                )
            }
        }
        val node = compose.onNodeWithTag(settingsTag("system"))
        node.assertIsSelected()
        assertEquals(Role.Button, node.fetchSemanticsNode().config[SemanticsProperties.Role])
        node.performClick()
        compose.waitForIdle()
        assertEquals("one click per tap", 1, fired)
    }

    @Test
    fun theRowMergesTitleSubtitleAndValueIntoOneNode() {
        host {
            Column {
                GroupedRow(
                    "MacBook Pro",
                    GroupedPosition.Single,
                    subtitle = "Connected",
                    accessories = listOf(GroupedAccessory.Value("v1.2.3")),
                    testTag = "device",
                    onClick = null,
                )
            }
        }
        val texts = compose.onNodeWithTag("device")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }
            ?: ""
        assertTrue("merged text should contain the title: '$texts'", texts.contains("MacBook Pro"))
        assertTrue("merged text should contain the subtitle: '$texts'", texts.contains("Connected"))
        assertTrue("merged text should contain the value: '$texts'", texts.contains("v1.2.3"))
        assertTrue(
            "read order should be title, subtitle, value: '$texts'",
            texts.indexOf("MacBook Pro") < texts.indexOf("Connected") &&
                texts.indexOf("Connected") < texts.indexOf("v1.2.3"),
        )
    }

    @Test
    fun headersAreHeadingsAndBlankHeadersAreSilent() {
        host {
            Column {
                GroupedHeader("Appearance")
                GroupedHeader(" ")
                GroupedRow("x", GroupedPosition.Single, testTag = "r", onClick = null)
            }
        }
        val headings = compose
            .onAllNodes(SemanticsMatcher("is a heading") { it.config.contains(SemanticsProperties.Heading) })
            .fetchSemanticsNodes()
        assertEquals("only the text header is a heading", 1, headings.size)
        // No node carries the blank header's (nonexistent) text.
        val blankText = compose.onAllNodesWithText(" ", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the blank header must stay silent", blankText.isEmpty())
    }

    @Test
    fun theRowTextStyleOverrideWins() {
        host {
            Column {
                GroupedRow(
                    "..",
                    GroupedPosition.Single,
                    titleStyle = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13f), Color.Unspecified),
                    testTag = "dotdot",
                    onClick = null,
                )
            }
        }
        // The override row's title node is shorter than the 20.8 dp line box
        // of the default 16 dp style (13 dp → 16.9 dp), proving the style
        // was replaced, not merged.
        val title = compose.onNodeWithText("..", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(heightOf(title), 16.9.dp, "overridden title line box")
    }
}
