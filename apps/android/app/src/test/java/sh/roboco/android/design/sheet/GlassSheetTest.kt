package sh.roboco.android.design.sheet

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The sheet's behaviour (ticket 17) — Robolectric + compose-ui-test at
 * mdpi (1 dp = 1 px, the GlassButtonsTest convention) in a 360 × 640
 * window, so drag distances read straight off pixels. The status-bar
 * inset under Robolectric is whatever the window reports; every assertion
 * reads the actual anchors off the state, never hard-codes them.
 *
 * Covers the ticket's list: the anchors after layout, the present
 * animation ending at the smallest detent, drag-past-threshold dismissal
 * and spring-back, the scrim's tap rules at Medium and Large (and the
 * unreachable content behind), predictive back commit and cancel, a
 * content fling that hits the list's top not dismissing, `onDismiss`
 * firing exactly once on every path, and the header's 52 dp + button
 * tags, plus the standalone (ticket 31/101) reuse of [SheetHeaderRow] and
 * [SheetGrabber].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class GlassSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var state: GlassSheetState? = null
    private var dismissals = 0
    private var behindClicks = 0

    /** One composed sheet configuration; a new instance re-keys the sheet. */
    private class SheetConfig(
        val detents: List<SheetDetent>,
        val grabber: Boolean,
        val header: (@Composable GlassSheetScope.() -> Unit)?,
        val content: @Composable GlassSheetScope.(Dp) -> Unit,
        val onDismiss: () -> Unit,
    )

    private var config by mutableStateOf<SheetConfig?>(null)
    private var contentSet = false

    // ------------------------------------------------------------- helpers

    /**
     * Composes the sheet over a clickable "presenting" layer. A fresh
     * state each call: the sheet presents from `Hidden` to the smallest
     * detent; callers then wait for the settle or drive it themselves.
     *
     * The activity's content is set once per test (a second `setContent`
     * throws "has already set content"); a later call swaps [config], and
     * `key(config)` composes a brand-new sheet and state for it.
     */
    private fun composeSheet(
        detents: List<SheetDetent>,
        grabber: Boolean = true,
        header: (@Composable GlassSheetScope.() -> Unit)? = {
            SheetHeader("Bash", leading = SheetLeading.Close, trailing = SheetTrailing.Done)
        },
        content: @Composable GlassSheetScope.(Dp) -> Unit = { bottomInset -> SheetListContent(bottomInset) },
        onDismiss: () -> Unit = { dismissals++ },
    ) {
        dismissals = 0
        behindClicks = 0
        state = null
        val next = SheetConfig(detents, grabber, header, content, onDismiss)
        if (contentSet) {
            compose.runOnUiThread { config = next }
            return
        }
        config = next
        contentSet = true
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    // The presenting content: any tap the scrim fails to
                    // consume would land here.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("behind")
                            .clickable { behindClicks++ },
                    )
                    val c = config
                    if (c != null) {
                        key(c) {
                            val sheetState = rememberGlassSheetState(c.detents)
                            state = sheetState
                            GlassSheet(
                                onDismiss = c.onDismiss,
                                state = sheetState,
                                grabber = c.grabber,
                                title = "Bash",
                                header = c.header,
                                content = c.content,
                            )
                        }
                    }
                }
            }
        }
    }

    /** The sheet's long list: 200 rows, tagged for scroll/fling actions. */
    @Composable
    private fun SheetListContent(bottomInset: Dp) {
        val typeScale = LocalTypeScale.current
        val palette = RobocoTheme.palette
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .testTag("sheet-list"),
        ) {
            items(200) { line ->
                BasicText(
                    "line ${line + 1}",
                    Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                    style = uiStyle(Face.Mono, typeScale.bodyScaled(13f), palette.text),
                )
            }
        }
    }

    /** Presents the sheet to its smallest detent and settles the spring. */
    private fun presentAndSettle() {
        compose.waitForIdle()
        // The present spring is 0.5 s; with a frozen clock the frames only
        // run when we advance them, with auto-advance the first wait already
        // ran them.
        if (!compose.mainClock.autoAdvance) {
            compose.mainClock.advanceTimeBy(1100)
            compose.waitForIdle()
        }
    }

    private fun anchor(detent: SheetDetent): Float = state!!.anchors.positionOf(detent)

    /**
     * A slow vertical drag on the sheet's header band that moves the sheet
     * [distance] px down. `anchoredDraggable` drops the touch slop before
     * the drag starts (only the over-slop delta moves the sheet), so the
     * finger travels `distance + touchSlop` — without it a "26 %" drag
     * moved the sheet only ≈23 % (CI r17 settled back at Medium).
     */
    private fun dragHeader(distance: Float) {
        compose.onNodeWithTag("glass-sheet").performTouchInput {
            val travel = distance + viewConfiguration.touchSlop
            swipe(
                start = Offset(centerX, 26f),
                end = Offset(centerX, 26f + travel),
                durationMillis = 500,
            )
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- anchors

    @Test
    fun anchorsAfterLayoutMatchTheGeometryRule() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        val s = state!!
        // H = 640, sb read from the window: Large = sb + 10, Medium = 320,
        // Hidden = 640, and Medium must clear Large.
        val sb = anchor(SheetDetent.Large) - 10f
        assertEquals(640f, anchor(SheetDetent.Hidden), 0.01f)
        assertEquals(320f, anchor(SheetDetent.Medium), 0.01f)
        assertTrue(anchor(SheetDetent.Medium) > sb + 10f + 1f)
        // A large-only sheet: no Medium anchor at all.
        composeSheet(listOf(SheetDetent.Large))
        presentAndSettle()
        assertTrue(state!!.anchors.positionOf(SheetDetent.Medium).isNaN())
        assertEquals(640f, anchor(SheetDetent.Hidden), 0.01f)
    }

    @Test
    fun presentEndsAtTheSmallestDetent() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        assertEquals(SheetDetent.Medium, state!!.current)
        assertEquals(anchor(SheetDetent.Medium), state!!.offset, 1f)
        assertEquals(0, dismissals)

        // Large-only presents to Large.
        composeSheet(listOf(SheetDetent.Large))
        presentAndSettle()
        assertEquals(SheetDetent.Large, state!!.current)
        assertEquals(anchor(SheetDetent.Large), state!!.offset, 1f)
    }

    // ------------------------------------------------------- drag dismissal

    @Test
    fun dragPastTheThresholdDismissesOnce() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        val span = anchor(SheetDetent.Hidden) - anchor(SheetDetent.Medium)
        // 26 % of Medium→Hidden, released slowly (the swipe is 500 ms, so
        // the release velocity is far below the 1200 dp/s fling line).
        dragHeader(span * 0.26f)
        settle()
        assertEquals(SheetDetent.Hidden, state!!.current)
        assertEquals(1, dismissals)
    }

    @Test
    fun shortDragSpringsBackToTheDetent() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        val span = anchor(SheetDetent.Hidden) - anchor(SheetDetent.Medium)
        dragHeader(span * 0.20f) // 20 %: below the 25 % threshold
        settle()
        assertEquals(SheetDetent.Medium, state!!.current)
        assertEquals(anchor(SheetDetent.Medium), state!!.offset, 1f)
        assertEquals(0, dismissals)
    }

    // --------------------------------------------------------- the scrim

    @Test
    fun scrimTapAtMediumDismissesAndNothingBehindIsClicked() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        // A tap above the sheet (the sheet top is at the Medium anchor).
        compose.onNodeWithTag("glass-sheet-scrim").performTouchInput {
            click(Offset(centerX, anchor(SheetDetent.Medium) / 2f))
        }
        settle()
        assertEquals(1, dismissals)
        assertEquals(0, behindClicks)
        assertEquals(SheetDetent.Hidden, state!!.current)
    }

    @Test
    fun scrimTapAtLargeDoesNothingAndStillBlocksTheContent() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Large))
        presentAndSettle()
        // The only scrim strip at Large is above the sheet's top.
        compose.onNodeWithTag("glass-sheet-scrim").performTouchInput {
            click(Offset(centerX, anchor(SheetDetent.Large) / 2f))
        }
        settle()
        assertEquals(0, dismissals)
        assertEquals(0, behindClicks)
        assertEquals(SheetDetent.Large, state!!.current)
    }

    @Test
    fun doneTapDismissesOnce() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        compose.onNodeWithTag("sheet-done").performClick()
        settle()
        assertEquals(1, dismissals)
        assertEquals(SheetDetent.Hidden, state!!.current)
    }

    @Test
    fun dismissActionTwiceCallsOnDismissOnce() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        repeat(2) {
            compose.onNodeWithTag("glass-sheet").performSemanticsAction(SemanticsActions.Dismiss)
        }
        settle()
        assertEquals(1, dismissals)
        assertEquals(SheetDetent.Hidden, state!!.current)
    }

    // ------------------------------------------------------- system back

    @Test
    fun predictiveBackCommitDismisses() {
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(100f, 100f, 1f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()
        // 25 % of the way to hidden at progress 1.
        val start = anchor(SheetDetent.Medium)
        val expected = start + 0.25f * (anchor(SheetDetent.Hidden) - start)
        assertEquals(expected, state!!.offset, 1f)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, dismissals)
        assertEquals(SheetDetent.Hidden, state!!.current)
    }

    @Test
    fun predictiveBackCancelSpringsBackToTheDetent() {
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(100f, 100f, 0.6f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(0, dismissals)
        assertEquals(SheetDetent.Medium, state!!.current)
        assertEquals(anchor(SheetDetent.Medium), state!!.offset, 1f)
    }

    // --------------------------------------------------- scroll coupling

    @Test
    fun contentFlingThatHitsTheTopDoesNotDismiss() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        assertEquals(SheetDetent.Medium, state!!.current)
        // Expand to Large: an upward swipe at Medium moves the sheet first
        // (the scroll coupling), and the release settles it at Large.
        compose.onNodeWithTag("sheet-list").performTouchInput { swipeUp() }
        settle()
        assertEquals(SheetDetent.Large, state!!.current)
        assertEquals(0, dismissals)
        // Scroll into the list: at Large the list takes the deltas.
        repeat(2) {
            compose.onNodeWithTag("sheet-list").performTouchInput { swipeUp() }
            settle()
        }
        // A fast downward fling: the list consumes the drag while it has
        // room above, the fling then reaches the list's top and its leftover
        // velocity must NOT settle or dismiss the sheet (iOS bounces the
        // scroll view instead).
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(Offset(centerX, 200f), Offset(centerX, 500f), durationMillis = 100)
        }
        compose.mainClock.advanceTimeBy(3000) // the list's fling runs out
        compose.waitForIdle()
        assertEquals(0, dismissals)
        assertEquals(SheetDetent.Large, state!!.current)
    }

    @Test
    fun pullDownAtTheListTopMovesTheSheet() {
        compose.mainClock.autoAdvance = false
        composeSheet(listOf(SheetDetent.Medium, SheetDetent.Large))
        presentAndSettle()
        assertEquals(SheetDetent.Medium, state!!.current)
        // The list is at its top: a downward drag has nothing for the list
        // to consume, so the sheet takes it (the nested-scroll post pass).
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(
                start = Offset(centerX, 200f),
                end = Offset(centerX, 264f),
                durationMillis = 500,
            )
        }
        // Mid-gesture (before the release settles): the sheet moved down.
        compose.waitForIdle()
        val during = state!!.offset
        assertTrue(
            "the sheet must follow a pull-down at the list top, was $during",
            during > anchor(SheetDetent.Medium),
        )
        settle()
        // 64 px of the 320 px span (20 %): springs back to Medium.
        assertEquals(SheetDetent.Medium, state!!.current)
        assertEquals(0, dismissals)
    }

    // ------------------------------------------------------------- header

    @Test
    fun headerRowIs52DpHighWithBothButtonTagsStandalone() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    SheetHeaderRow(
                        "Bash",
                        leading = SheetLeading.Close,
                        trailing = SheetTrailing.Done,
                        modifier = Modifier.testTag("row"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("row").assertHeightIsEqualTo(52.dp)
        compose.onNodeWithTag("sheet-close").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("sheet-done").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
        // The title is a heading and centred on the row width.
        compose.onNodeWithText("Bash").assertExists()
        val row = compose.onNodeWithTag("row").fetchSemanticsNode()
        val title = compose.onNodeWithText("Bash").fetchSemanticsNode()
        val rowBounds = row.positionInRoot
        val titleBounds = title.positionInRoot
        assertEquals(
            rowBounds.x + row.size.width / 2f,
            titleBounds.x + title.size.width / 2f,
            1f,
        )
    }

    @Test
    fun grabberIsA44x20TargetWithUikitLabelsStandalone() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    SheetGrabber(
                        Modifier.testTag("grabber"),
                        onClick = {},
                        stateDescription = "Half screen",
                    )
                    SheetGrabber(Modifier.padding(top = 60.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("grabber").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(20.dp)
        compose.onNodeWithContentDescription("Sheet Grabber").assertExists()
        // Only the interactive grabber is announced: the plain one is
        // invisible to accessibility.
        assertEquals(1, compose.onAllNodesWithContentDescription("Sheet Grabber").fetchSemanticsNodes().size)
    }
}
