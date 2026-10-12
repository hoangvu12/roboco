package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import sh.roboco.android.design.glass.GlassTextCapsule

/**
 * [LargeTitleScaffold]'s rendering and behaviour (android-ui-parity #25):
 * Robolectric + `createComposeRule` at `w412dp-h700dp-xxhdpi` (1 dp = 3 px;
 * Robolectric reports no insets, so [LargeTitleFixtureScaffold] fixes
 * `topInset = 24.dp` and `navBottom = 68`). `showBack`/`onBack` are passed
 * explicitly everywhere outside a `NavStackHost` — 24's locals have no
 * default.
 *
 * Static scroll positions are composed in via [LazyListState]'s initial
 * index/offset (no runtime scroll, so the snap effect never fires while
 * asserting geometry); the snap, its interruption and the touch blocking use
 * real gestures. The goldens live in
 * `LargeTitleScaffoldScreenshotTest`; the pure math in
 * `LargeTitleMetricsTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
class LargeTitleScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    // ----------------------------------------------------------- helpers

    /** px of [v] dp at the test density (3). */
    private fun dp(v: Float): Float = with(compose.density) { v.dp.toPx() }

    /** Composes the fixture and settles. */
    private fun host(
        mode: TitleMode = TitleMode.Always,
        showBack: Boolean = false,
        title: String = "Sessions",
        listState: LazyListState = LazyListState(),
        rows: Int = 30,
        onBack: () -> Unit = {},
        onRowTap: () -> Unit = {},
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleFixtureScaffold(
                    mode = mode,
                    showBack = showBack,
                    title = title,
                    listState = listState,
                    rows = rows,
                    onBack = onBack,
                    onRowTap = onRowTap,
                )
            }
        }
        compose.waitForIdle()
    }

    /** The live collapse distance `c` of [listState], in dp. */
    private fun collapseOf(listState: LazyListState): Float = with(compose.density) {
        LargeTitleMetrics.collapse(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset.toDp().value)
    }

    /** Nodes with the title text that TalkBack can see (a heading, not hidden). */
    private fun visibleTitleHeadings(title: String): List<SemanticsNode> =
        compose.onAllNodes(hasText(title)).fetchSemanticsNodes()
            .filter { it.config.contains(SemanticsProperties.Heading) }
            .filterNot { it.config.contains(SemanticsProperties.HideFromAccessibility) }

    /** The node under [tag]'s semantics config. */
    private fun configOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config

    private fun isHidden(tag: String): Boolean =
        configOf(tag).contains(SemanticsProperties.HideFromAccessibility)

    /**
     * A slow, zero-velocity swipe of [distanceDp] upward (content scrolls
     * up, `c` grows), starting well below the nav row: no fling, so the
     * release state is exactly the drag state.
     */
    private fun dragUp(distanceDp: Float, startYFraction: Float = 0.6f) {
        compose.onRoot().performTouchInput {
            swipeWithVelocity(
                start = Offset(width / 2f, height * startYFraction),
                end = Offset(width / 2f, height * startYFraction - dp(distanceDp)),
                endVelocity = 0f,
            )
        }
    }

    // ------------------------------------------------------- geometry

    @Test
    fun largeTitleGeometryAtRest() {
        host(rows = 10)
        // topInset = 24 → navBottom = 68; the text box (natural 39 dp line +
        // 8 dp bottom inset) starts at 68 + 5 = 73, leading edge 16.
        val node = compose.onNodeWithTag("nav-large-title").fetchSemanticsNode()
        assertEquals(dp(16f), node.positionInRoot.x, dp(1f))
        assertEquals(dp(73f), node.positionInRoot.y, dp(2f))
    }

    @Test
    fun inlineTitleIsCentredOnTheFullWidthWhenCollapsed() {
        host(listState = LazyListState(3, 0))
        val inline = compose.onNodeWithTag("nav-inline-title").fetchSemanticsNode()
        // Centre x = screen width / 2 (412 / 2), centre y = 24 + 22 = 46:
        // the 22.1 dp line box centred in the 44 dp nav row.
        assertEquals(dp(206f), inline.positionInRoot.x + inline.size.width / 2f, dp(1f))
        assertEquals(dp(46f), inline.positionInRoot.y + inline.size.height / 2f, dp(1f))
    }

    @Test
    fun trailingCircleIsAFortyFourSquareAtTrailingSixteen() {
        host(rows = 10)
        val more = compose.onNodeWithContentDescription("More").fetchSemanticsNode()
        assertEquals(dp(44f), more.size.width.toFloat(), 1f)
        assertEquals(dp(44f), more.size.height.toFloat(), 1f)
        assertEquals(dp(412f - 16f), more.positionInRoot.x + more.size.width, dp(1f))
        assertEquals(dp(46f), more.positionInRoot.y + more.size.height / 2f, dp(1f))
    }

    @Test
    fun backCircleGeometryAndTap() {
        var backTaps = 0
        host(showBack = true, onBack = { backTaps++ }, rows = 10)
        val back = compose.onNodeWithTag("nav-back").fetchSemanticsNode()
        assertEquals(dp(44f), back.size.width.toFloat(), 1f)
        assertEquals(dp(16f), back.positionInRoot.x, dp(1f))
        assertEquals(dp(46f), back.positionInRoot.y + back.size.height / 2f, dp(1f))
        compose.onNodeWithTag("nav-back")
            .assert(hasContentDescription("Back"))
            .performClick()
        compose.runOnIdle { assertEquals("onBack was not called once", 1, backTaps) }
    }

    @Test
    fun longTitleNarrowsToStayClearOfBothBarItems() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleScaffold(
                    title = "Durable Object hibernation flush and cleanup",
                    topInset = 24.dp,
                    showBack = true,
                    onBack = {},
                    trailing = { GlassTextCapsule("Edit", onClick = {}) },
                ) { largeTitleFixtureRows(10) }
            }
        }
        compose.waitForIdle()
        val inline = compose.onNodeWithTag("nav-inline-title").fetchSemanticsNode()
        val back = compose.onNodeWithTag("nav-back").fetchSemanticsNode()
        val edit = compose.onNodeWithText("Edit").fetchSemanticsNode()
        // Clear of the back circle on the leading side …
        assertTrue(
            "inline title overlaps the back circle",
            inline.positionInRoot.x >= back.positionInRoot.x + back.size.width,
        )
        // … and of the Edit capsule on the trailing side.
        assertTrue(
            "inline title overlaps the trailing item",
            inline.positionInRoot.x + inline.size.width <= edit.positionInRoot.x,
        )
        // And bounded by the formula: width − 2·(max extent + 8) ≤ 240.
        assertTrue("inline title wider than its max", inline.size.width <= dp(240f))
    }

    // ------------------------------------------- the collapse and its a11y

    @Test
    fun exactlyOneTitleIsExposedAtRestMidAndCollapsed() {
        // c = 0: the large title only.
        host(listState = LazyListState())
        assertTrue(isHidden("nav-inline-title"))
        assertFalse(isHidden("nav-large-title"))
        assertEquals(1, visibleTitleHeadings("Sessions").size)

        // c = 35: still below the 0.5 flip (alpha 0.49) — the large title.
        host(listState = LazyListState(0, dp(35f).toInt()))
        assertEquals(1, visibleTitleHeadings("Sessions").size)

        // c = 52: the inline title; the large-title item is still composed
        // in the padding region, so it must be hidden.
        host(listState = LazyListState(3, 0))
        assertFalse(isHidden("nav-inline-title"))
        assertTrue(isHidden("nav-large-title"))
        assertEquals(1, visibleTitleHeadings("Sessions").size)
    }

    @Test
    fun inlineTitleHiddenAtRestExposedWhenCollapsed() {
        host(listState = LazyListState())
        assertTrue(isHidden("nav-inline-title"))
        host(listState = LazyListState(0, dp(60f).toInt()))
        assertFalse(isHidden("nav-inline-title"))
    }

    @Test
    fun navRowIsATraversalGroupReadBeforeTheContent() {
        host(rows = 10)
        val navRowGroups = compose.onAllNodes(
            SemanticsMatcher("nav row traversal group") {
                it.config.contains(SemanticsProperties.IsTraversalGroup) &&
                    it.config.contains(SemanticsProperties.TraversalIndex) &&
                    it.config[SemanticsProperties.TraversalIndex] == -1f
            },
        ).fetchSemanticsNodes()
        assertEquals("exactly the nav row is a −1 traversal group", 1, navRowGroups.size)
    }

    // ------------------------------------------------------- the snap

    @Test
    fun releaseJustPastHalfSnapsClosed() {
        val listState = LazyListState()
        host(listState = listState)
        dragUp(35f)
        compose.waitForIdle()
        assertEquals("c should have snapped to 52", 52f, collapseOf(listState), 0.5f)
    }

    @Test
    fun releaseBelowHalfSnapsOpen() {
        val listState = LazyListState()
        host(listState = listState)
        dragUp(20f)
        compose.waitForIdle()
        assertEquals("c should have snapped to 0", 0f, collapseOf(listState), 0.5f)
    }

    @Test
    fun snapSurvivesInterruptionByASecondDrag() {
        val listState = LazyListState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleFixtureScaffold(listState = listState)
            }
        }
        compose.mainClock.advanceTimeBy(100)
        // 1. A drag that leaves the title just under the 50 % threshold.
        dragUp(20f)
        // 2. Frames: the release's snap (to 0) starts and runs.
        compose.mainClock.advanceTimeBy(100)
        // 3. A second drag interrupts it mid-flight; without the snap's
        //    CancellationException guard this is where the collector dies.
        compose.onRoot().performTouchInput {
            swipeWithVelocity(
                start = Offset(width / 2f, height * 0.5f),
                end = Offset(width / 2f, height * 0.5f - dp(35f)),
                endVelocity = 0f,
            )
        }
        // 4. The release of the second drag snaps again.
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val c = collapseOf(listState)
        assertTrue("settled at c = $c, not a snap target (0 or 52)", c < 0.5f || c > 51.5f)
    }

    // ------------------------------------------------------- hit testing

    @Test
    fun navRowBlocksTapsUnderItButTheExtensionPassesThrough() {
        var taps = 0
        // Index 3: rows sit under the bar; row 3 fills 68 … 130 dp.
        host(listState = LazyListState(3, 0), onRowTap = { taps++ })
        compose.onRoot().performTouchInput { click(Offset(width / 2f, dp(34f))) }
        compose.runOnIdle { assertEquals("the nav row swallowed a tap into the bar", 0, taps) }
        compose.onRoot().performTouchInput { click(Offset(width / 2f, dp(76f))) }
        compose.runOnIdle { assertEquals("the edge extension blocked a tap", 1, taps) }
    }

    // ------------------------------------------------------- modes

    @Test
    fun inlineOnlyHasNoLargeTitleAndContentStartsAtNavBottom() {
        host(mode = TitleMode.InlineOnly)
        compose.onNodeWithTag("nav-large-title").assertDoesNotExist()
        assertFalse(isHidden("nav-inline-title"))
        assertEquals(dp(68f), compose.onNodeWithTag("fixture-row-0").fetchSemanticsNode().positionInRoot.y, dp(1f))
    }

    @Test
    fun noneModeHasNoChromeAndContentStartsAtZero() {
        host(mode = TitleMode.None)
        compose.onNodeWithTag("nav-large-title").assertDoesNotExist()
        compose.onNodeWithTag("nav-inline-title").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").assertDoesNotExist()
        assertEquals(dp(0f), compose.onNodeWithTag("fixture-row-0").fetchSemanticsNode().positionInRoot.y, dp(1f))
    }

    @Test
    fun prefersLargeTitlesFalseDemotesAlwaysToInlineOnly() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalPrefersLargeTitles provides false) {
                    LargeTitleFixtureScaffold()
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-large-title").assertDoesNotExist()
        assertFalse(isHidden("nav-inline-title"))
        assertEquals(dp(68f), compose.onNodeWithTag("fixture-row-0").fetchSemanticsNode().positionInRoot.y, dp(1f))
    }

    // ------------------------------------------------------- title changes

    @Test
    fun titleChangeUpdatesBothTitlesWithoutResettingScroll() {
        var title by mutableStateOf("Sessions")
        val listState = LazyListState(3, 0)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleFixtureScaffold(title = title, listState = listState, rows = 10)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { title = "Renamed" }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-inline-title").assert(hasText("Renamed"))
        compose.onNodeWithTag("nav-large-title").assert(hasText("Renamed"))
        compose.runOnIdle {
            assertEquals("the scroll position was reset", 3, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
    }

    // ------------------------------------------------------- root vs pushed

    @Test
    fun rootHasNoBackCircleAndAPushedScreenHasOne() {
        val stack = NavStack(DemoRoute("Sessions"))
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NavStackHost(stack = stack, active = true) { entry ->
                    LargeTitleScaffold(title = entry.route.paneTitle.orEmpty()) {
                        largeTitleFixtureRows(6)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the root screen shows a back circle", 0, compose.onAllNodesWithTag("nav-back").fetchSemanticsNodes().size)
        compose.runOnIdle { stack.push(DemoRoute("Folder")) }
        compose.waitForIdle()
        assertEquals("the pushed screen does not show a back circle", 1, compose.onAllNodesWithTag("nav-back").fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------- recomposition

    @Test
    fun scrollingDoesNotRecomposeTheScaffoldPerFrame() {
        LargeTitleRecompositionProbe.reset()
        val listState = LazyListState()
        host(listState = listState)
        val initial = LargeTitleRecompositionProbe.count
        dragUp(300f, startYFraction = 0.8f)
        compose.waitForIdle()
        val recompositions = LargeTitleRecompositionProbe.count - initial
        assertTrue(
            "the scaffold recomposed $recompositions times during a 300 dp scroll (budget: 2)",
            recompositions <= 2,
        )
    }

    // ------------------------------------------------------- pinned accessory

    @Test
    fun pinnedAccessoryRidesUpAndPinsUnderTheNavRow() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleScaffold(
                    title = "Sessions",
                    topInset = 24.dp,
                    showBack = false,
                    onBack = {},
                    pinnedAccessory = { Box(Modifier.testTag("accessory").fillMaxSize()) },
                    pinnedAccessoryHeight = 40.dp,
                ) { largeTitleFixtureRows(10) }
            }
        }
        compose.waitForIdle()
        // At rest: under the large title (68 + 52); rows start below it.
        assertEquals(dp(120f), compose.onNodeWithTag("accessory").fetchSemanticsNode().positionInRoot.y, dp(1f))
        assertEquals(dp(160f), compose.onNodeWithTag("fixture-row-0").fetchSemanticsNode().positionInRoot.y, dp(1f))

        // Scrolled 100 dp (52 + 48 → index 1, offset 48): pinned at navBottom.
        val listState = LazyListState(1, dp(48f).toInt())
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleScaffold(
                    title = "Sessions",
                    listState = listState,
                    topInset = 24.dp,
                    showBack = false,
                    onBack = {},
                    pinnedAccessory = { Box(Modifier.testTag("accessory").fillMaxSize()) },
                    pinnedAccessoryHeight = 40.dp,
                ) { largeTitleFixtureRows(10) }
            }
        }
        compose.waitForIdle()
        assertEquals(dp(68f), compose.onNodeWithTag("accessory").fetchSemanticsNode().positionInRoot.y, dp(1f))
    }

    // ------------------------------------------------------- content padding

    @Test
    fun callerContentPaddingIsDelegatedPerSide() {
        val callerPadding = RecordingPadding(bottom = 40.dp)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleScaffold(
                    title = "Sessions",
                    topInset = 24.dp,
                    showBack = false,
                    onBack = {},
                    contentPadding = callerPadding,
                ) { largeTitleFixtureRows(30) }
            }
        }
        compose.waitForIdle()
        assertTrue("the caller's bottom padding was never consulted", callerPadding.bottomReads > 0)
        // The scaffold's own top padding is added on top of the delegation.
        assertEquals(dp(68f), compose.onNodeWithTag("fixture-row-0").fetchSemanticsNode().positionInRoot.y, dp(1f))
    }

    /** A caller's [PaddingValues] that counts its bottom reads (measure-time delegation). */
    private class RecordingPadding(val bottom: Dp) : PaddingValues {
        var bottomReads = 0

        override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

        override fun calculateTopPadding(): Dp = 0.dp

        override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp

        override fun calculateBottomPadding(): Dp {
            bottomReads++
            return bottom
        }
    }
}
