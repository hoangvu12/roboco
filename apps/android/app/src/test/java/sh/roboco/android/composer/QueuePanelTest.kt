package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
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
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassProgressKey
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.session.SessionChrome
import kotlin.math.abs

/**
 * [QueuePanel] — android-ui-parity #82's acceptance criteria (Robolectric
 * + compose-ui-test). The window is the ticket's phone slice: **402 dp
 * wide at xxhdpi (density 3)**, with the panel composing in a 378 dp slot
 * at the window's top-start (the bottom stack width on this phone), so
 * the card lands at x = 10…368 dp and every dp tolerance below is 1 px.
 * Glass runs its forced fallback tier (`Glass.forceFallback`, the
 * GalleryCapture convention) so the pixel probes read exact composites;
 * the Roborazzi goldens cover the drawn material.
 *
 *  - the card geometry: x = 10…368, heights 48 / 88 / 128 / 152 dp for
 *    F1 / F2L / F3 / F5, rows 40 dp, the 24 dp footer with its text
 *    centred;
 *  - the row anatomy: label start 35 dp in, Send 48 dp wide ending at
 *    card right − 44, More 40 dp ending at card right − 4, no gap between
 *    them, the label ending ≥ 4 dp before Send, the clock ink centred at
 *    20.5 dp;
 *  - the `testQueueWhileWorking` regression: the long second row fades at
 *    its tail (the measured text is wider than the 227 dp node) and both
 *    ⋯ buttons stay on the card;
 *  - the actions: Send fires `onAction("q2", SendNow)`; a Remove pick
 *    fires after the menu starts closing (ticket 15's next-frame rule);
 *    `writable = false` disables both buttons, draws them at 0.4 and
 *    swallows taps;
 *  - the glass: a first render with a queue is materialized (the
 *    `testQueueSurvivesNavigatingAway` port), an empty queue growing in
 *    animates 350 ms ease-in-out, dematerializing leaves the semantics
 *    tree at once while still fading over 250 ms;
 *  - the measured height snaps between row counts (no inner animation);
 *  - a fired action changes nothing on the panel (decision COMPOSER-D2:
 *    no error UI anywhere).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class QueuePanelTest {

    @get:Rule
    val compose = createComposeRule()

    /** The qualifier's xxhdpi. */
    private val density = 3f

    /** 1 px in dp at density 3 — the ticket's tolerances. */
    private val px = 0.34f

    /** Every row action the panel fired, in order. */
    private val actions = mutableListOf<Pair<String, QueueAction>>()

    /** The long row text's measured width in dp, filled by [LongTextProbe]. */
    private var measuredLongTextDp = -1f

    @Before
    fun forceFallbackGlass() {
        Glass.forceFallback = true
        actions.clear()
        measuredLongTextDp = -1f
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        compose.mainClock.autoAdvance = true
    }

    // ------------------------------------------------------------ harness

    /** The swappable panel inputs; one setContent per test. */
    private class Placement(
        val items: List<SessionChrome.QueuedItem>,
        val glassVisible: Boolean,
        val animateGlass: Boolean,
        val writable: Boolean,
    )

    private var placement by mutableStateOf<Placement?>(null)

    /**
     * Renders the panel in a 378 dp slot at the window's top-start over a
     * white page, inside the host wiring the ticket names
     * (`RobocoTheme { ProvideHazeState { GlassMenuHost { … } } }`). The
     * first call mounts; later calls swap the inputs. The frozen-clock
     * recipe (write → notifications → one frame) runs after every call.
     */
    private fun place(
        items: List<SessionChrome.QueuedItem> = QueuePanelFixtures.F1,
        glassVisible: Boolean = true,
        animateGlass: Boolean = false,
        writable: Boolean = true,
    ) {
        val mounted = placement != null
        placement = Placement(items, glassVisible, animateGlass, writable)
        if (!mounted) {
            compose.setContent {
                val p = placement ?: return@setContent
                RobocoTheme(Appearance.Light) {
                    ProvideHazeState {
                        GlassMenuHost(hazeState = LocalHazeState.current!!) {
                            Box(Modifier.fillMaxSize().background(Color.White)) {
                                QueuePanel(
                                    items = p.items,
                                    glassVisible = p.glassVisible,
                                    animateGlass = p.animateGlass,
                                    writable = p.writable,
                                    onAction = { id, action -> actions += id to action },
                                    modifier = Modifier.width(378.dp),
                                )
                                LongTextProbe { measuredLongTextDp = it }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * Measures the long row text in the row's own style (Geist Regular 15,
     * single line, no wrap) so the overflow verdict is observable: the
     * full text is wider than the 227 dp label slot.
     */
    @Composable
    private fun LongTextProbe(widthDp: (Float) -> Unit) {
        val measurer = rememberTextMeasurer()
        val style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), RobocoTheme.palette.text)
        val localDensity = LocalDensity.current
        SideEffect {
            val layout = measurer.measure(
                AnnotatedString(QueuePanelFixtures.LongRowText),
                style,
                softWrap = false,
                maxLines = 1,
            )
            widthDp(with(localDensity) { layout.size.width.toDp().value })
        }
    }

    private fun boundsOf(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    private fun DpRect.heightDp(): Float = bottom.value - top.value

    private fun DpRect.widthDp(): Float = right.value - left.value

    private fun hasGlassProgress(): SemanticsMatcher =
        SemanticsMatcher("has GlassProgress") { it.config.contains(GlassProgressKey) }

    /** The card's materialization progress, read from the semantics tree. */
    private fun progress(): Float {
        val nodes = compose.onAllNodes(hasGlassProgress()).fetchSemanticsNodes()
        assertTrue("no GlassProgressKey node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[GlassProgressKey]
    }

    // ------------------------------------------------------- pixel probes

    private fun pixels(tag: String): PixelMap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    /** ± [tolerance] 8-bit levels on every channel. */
    private fun isNear(c: Color, target: Color, tolerance: Int): Boolean {
        fun channel(color: Color, shift: Int) = (color.toArgb() shr shift) and 0xFF
        return abs(channel(c, 16) - channel(target, 16)) <= tolerance &&
            abs(channel(c, 8) - channel(target, 8)) <= tolerance &&
            abs(channel(c, 0) - channel(target, 0)) <= tolerance
    }

    private fun countNear(map: PixelMap, target: Color, tolerance: Int = 12): Int {
        var n = 0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                if (isNear(map[x, y], target, tolerance)) n++
            }
        }
        return n
    }

    /** The ink bounding box of [target] within [map], or null when no pixel is near. */
    private fun inkBounds(map: PixelMap, target: Color, tolerance: Int = 12, untilX: Int = map.width): Rect? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until map.height) {
            for (x in 0 until minOf(untilX, map.width)) {
                if (isNear(map[x, y], target, tolerance)) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (minX == Int.MAX_VALUE) return null
        return Rect(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    // -------------------------------------------------------- geometry

    @Test
    fun cardGeometryMatchesTheSpec() {
        place(items = QueuePanelFixtures.F1)
        val card = boundsOf("queue-panel")
        assertEquals(10f, card.left.value, px)
        assertEquals(368f, card.right.value, px)
        assertEquals(0f, card.top.value, px)
        assertEquals("one row: 4 + 40 + 4 = 48", 48f, card.heightDp(), px)

        val row0 = boundsOf("queue-row-0")
        assertEquals(40f, row0.heightDp(), px)
        assertEquals("the row starts after the 4 dp column padding", 4f, row0.top.value - card.top.value, px)

        place(items = QueuePanelFixtures.F2L)
        assertEquals("two rows: 88", 88f, boundsOf("queue-panel").heightDp(), px)
        assertEquals(44f, boundsOf("queue-row-1").top.value - card.top.value, px)

        place(items = QueuePanelFixtures.F3)
        assertEquals("three rows: 128", 128f, boundsOf("queue-panel").heightDp(), px)

        place(items = QueuePanelFixtures.F5)
        val f5 = boundsOf("queue-panel")
        assertEquals("three rows + footer: 152", 152f, f5.heightDp(), px)
        val footer = boundsOf("queue-more-count")
        assertEquals(24f, footer.heightDp(), px)
        assertEquals("the footer starts after the third row", 124f, footer.top.value - f5.top.value, px)
        val footerText = compose.onNodeWithText("+2 more queued", useUnmergedTree = true).getBoundsInRoot()
        val leftGap = footerText.left.value - footer.left.value
        val rightGap = footer.right.value - footerText.right.value
        assertTrue("the footer text is centred (was $leftGap vs $rightGap)", abs(leftGap - rightGap) <= px)

        place(items = QueuePanelFixtures.FG)
        assertEquals("gates do not change the height", 152f, boundsOf("queue-panel").heightDp(), px)
        val gateRow = compose.onNodeWithTag("queue-row-text-0").fetchSemanticsNode()
        assertEquals("Editing · Then add a TL;DR.", gateRow.config[SemanticsProperties.Text].first().text)
    }

    @Test
    fun rowGeometryMatchesTheSpec() {
        place(items = QueuePanelFixtures.F2L)
        val card = boundsOf("queue-panel")
        val label = boundsOf("queue-row-text-1")
        val send = boundsOf("queue-send-1")
        val more = boundsOf("queue-more-1")

        assertEquals("the label starts at 14 + 13 + 8 = 35 dp", 35f, label.left.value - card.left.value, px)
        assertEquals(227f, label.widthDp(), px)
        assertEquals(48f, send.widthDp(), px)
        assertEquals("Send ends at card right − 44", card.right.value - 44f, send.right.value, px)
        assertEquals(40f, more.widthDp(), px)
        assertEquals("More ends at card right − 4", card.right.value - 4f, more.right.value, px)
        assertEquals("Send and More touch (no gap)", send.right.value, more.left.value, px)
        assertTrue(
            "the label ends ≥ 4 dp before Send (was ${label.right.value} vs ${send.left.value})",
            label.right.value <= send.left.value - 4f + px,
        )
    }

    @Test
    fun theClockGlyphIsCentredAt20Point5Dp() {
        place(items = QueuePanelFixtures.F2L)
        val rowPx = pixels("queue-row-0")
        // Only the columns before the label (x < 35 dp) are probed: the
        // anti-aliased edges of the label and button glyphs pass through
        // greys near tertiary and would stretch the box across the row.
        val ink = inkBounds(rowPx, Palette.Light.tertiary, untilX = (35f * density).toInt())
            ?: error("no tertiary ink in row 0 (the clock glyph)")
        val centreDp = ink.center.x / density
        assertEquals("the clock ink centres at x = 20.5 dp", 20.5f, centreDp, 0.5f)
    }

    // ------------------------------------------------ the row-1 regression

    @Test
    fun theLongSecondRowFadesAndKeepsBothMenusOnTheCard() {
        place(items = QueuePanelFixtures.F2L)
        val card = boundsOf("queue-panel")

        // The iOS regression: the long label never outweighs the row —
        // both ⋯ buttons exist, are displayed, and sit fully inside the card.
        val moreButtons = compose.onAllNodesWithContentDescription("More queue actions")
        moreButtons.assertCountEquals(2)
        val second = moreButtons[1]
        second.assertIsDisplayed()
        val bounds = second.getBoundsInRoot()
        assertTrue(
            "the second ⋯ lies inside the card (was $bounds vs $card)",
            bounds.left.value >= card.left.value - px &&
                bounds.right.value <= card.right.value + px &&
                bounds.top.value >= card.top.value - px &&
                bounds.bottom.value <= card.bottom.value + px,
        )

        // The text itself is wider than the 227 dp slot, so ticket 10
        // applies its 24 dp tail fade; semantics keep the full string.
        assertTrue("the probe measured the long text ($measuredLongTextDp dp)", measuredLongTextDp > 0f)
        assertTrue(
            "the measured text ($measuredLongTextDp dp) overflows the node (227 dp)",
            measuredLongTextDp > 227f + 8f,
        )
        val semantics = compose.onNodeWithTag("queue-row-text-1").fetchSemanticsNode()
        val text = semantics.config[SemanticsProperties.Text].first().text
        assertEquals(QueuePanelFixtures.LongRowText, text)

        // And clicking it opens the menu.
        compose.mainClock.autoAdvance = true
        second.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        compose.onNodeWithTag("queue-menu-remove").assertExists()
    }

    // ------------------------------------------------------------ actions

    @Test
    fun clickingSendFiresTheRowAction() {
        place(items = QueuePanelFixtures.F2L)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("queue-send-1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("q2" to QueueAction.SendNow), actions)
    }

    @Test
    fun pickingRemoveFiresAfterTheMenuStartsClosing() {
        place(items = QueuePanelFixtures.F2L)
        compose.mainClock.autoAdvance = true

        compose.onNodeWithTag("queue-more-0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        compose.onNodeWithTag("queue-menu-remove").assertExists()
        assertTrue("the pick has not fired yet", actions.isEmpty())

        // Ticket 15's order: the pick dismisses first, the action runs on
        // the next frame — while the menu fades.
        compose.onNodeWithTag("queue-menu-remove").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("q1" to QueueAction.Remove), actions)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun readOnlyRowsDisableBothButtonsAndSwallowTaps() {
        place(items = QueuePanelFixtures.F2L, writable = false)
        compose.onNodeWithTag("queue-send-0").assertIsNotEnabled()
        compose.onNodeWithTag("queue-send-1").assertIsNotEnabled()
        compose.onNodeWithTag("queue-more-0").assertIsNotEnabled()
        compose.onNodeWithTag("queue-more-1").assertIsNotEnabled()

        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("queue-send-0").performClick()
        compose.onNodeWithTag("queue-more-0").performClick()
        compose.waitForIdle()
        assertTrue("disabled buttons do nothing", actions.isEmpty())
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()

        // The glyphs draw at 0.4 (the token's DisabledAlpha) over the card.
        val cardBg = Glass.Light.fallback.compositeOver(Color.White)
        val sendPx = pixels("queue-send-0")
        assertTrue(
            "the ↑ glyph is drawn at 0.4 over the card",
            countNear(sendPx, Palette.Light.text.copy(alpha = QueuePanelTokens.DisabledAlpha).compositeOver(cardBg)) > 0,
        )
        assertEquals(
            "no full-strength ↑ ink remains",
            0,
            countNear(sendPx, Palette.Light.text.compositeOver(cardBg)),
        )
    }

    @Test
    fun aFiredActionChangesNothingOnThePanel() {
        // Decision COMPOSER-D2: no UI reacts to a failed or refused
        // action — no toast, no inline error line. The screen stays
        // exactly as it was apart from the row state the engine echoes.
        place(items = QueuePanelFixtures.F2L)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("queue-send-0").performClick()
        compose.waitForIdle()
        assertEquals(listOf("q1" to QueueAction.SendNow), actions)

        assertEquals(
            2,
            compose.onAllNodesWithContentDescription("More queue actions").fetchSemanticsNodes().size,
        )
        val row0 = boundsOf("queue-row-0")
        val row1 = boundsOf("queue-row-1")
        assertEquals(40f, row0.heightDp(), px)
        assertEquals(40f, row1.heightDp(), px)
        assertEquals("the row pitch is untouched", 40f, row1.top.value - row0.top.value, px)
        // Matched by the row's tag: in the unmerged tree FadingText's inner
        // BasicText carries the same text as its clearAndSetSemantics node,
        // so a text matcher finds two nodes.
        compose.onNodeWithTag("queue-row-text-0").assertTextEquals("Then add a TL;DR.")
    }

    // -------------------------------------------------------------- glass

    @Test
    fun aFirstRenderWithAQueueIsAlreadyMaterialized() {
        // The testQueueSurvivesNavigatingAway port: a reopened session
        // with a queue shows the card at once — animateGlass is false only
        // during the first composition (ticket 74's flag).
        compose.mainClock.autoAdvance = false
        place(items = QueuePanelFixtures.F1, glassVisible = true, animateGlass = false)
        assertEquals("p = 1 on the first frame", 1f, progress(), 1e-3f)
        compose.onNodeWithTag("queue-more-0").assertHasClickAction()

        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("queue-more-0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
    }

    @Test
    fun anEmptyQueueThatGrowsInMaterializesOver350Ms() {
        compose.mainClock.autoAdvance = false
        place(items = QueuePanelFixtures.F1, glassVisible = false, animateGlass = true)
        assertEquals(0f, progress(), 1e-3f)

        place(items = QueuePanelFixtures.F1, glassVisible = true, animateGlass = true)
        assertTrue("p is ~0 on the frame the flip lands (was ${progress()})", progress() < 0.05f)

        compose.mainClock.advanceTimeBy(175)
        val mid = progress()
        assertTrue("p ≈ 0.5 at +175 ms (was $mid)", mid in 0.35f..0.65f)
        compose.mainClock.advanceTimeBy(200)
        assertEquals("p = 1 at +350 ms", 1f, progress(), 0.02f)
    }

    @Test
    fun aDematerializingPanelLeavesTheSemanticsTreeAtOnce() {
        compose.mainClock.autoAdvance = false
        place(items = QueuePanelFixtures.F1, glassVisible = true, animateGlass = true)
        assertEquals(1f, progress(), 1e-3f)
        compose.onNodeWithTag("queue-more-0").assertExists()

        // The content leaves the tree the moment visible turns false —
        // the UIKit model-value rule — not when the fade finishes.
        place(items = QueuePanelFixtures.F1, glassVisible = false, animateGlass = true)
        compose.onNodeWithTag("queue-more-0").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(125)
        val mid = progress()
        assertTrue("still fading out over 250 ms (was $mid)", mid in 0.3f..0.7f)
    }

    // ------------------------------------------------------------- reflow

    @Test
    fun theMeasuredHeightSnapsBetweenRowCounts() {
        // No inner height animation: 74's slot-extent spring owns the
        // motion. The measured height is the new value on the very next
        // frame after the list changes.
        compose.mainClock.autoAdvance = false
        place(items = QueuePanelFixtures.F1)
        assertEquals(48f, boundsOf("queue-panel").heightDp(), px)

        place(items = QueuePanelFixtures.F2L)
        assertEquals("88 dp on the very next frame", 88f, boundsOf("queue-panel").heightDp(), px)
    }
}
