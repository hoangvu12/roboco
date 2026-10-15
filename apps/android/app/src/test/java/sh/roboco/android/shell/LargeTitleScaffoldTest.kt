package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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

    /**
     * The list position composed by [hostSwitchable]. A compose rule allows
     * one `setContent` per test, so tests that look at several static
     * positions swap this state instead; `key` gives every position a fresh
     * scaffold, exactly as a new composition would.
     */
    private var hostedListState by mutableStateOf(LazyListState())

    /** Composes the default fixture (as [host]) at [first]; see [showScroll]. */
    private fun hostSwitchable(first: LazyListState) {
        hostedListState = first
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                key(hostedListState) {
                    LargeTitleFixtureScaffold(listState = hostedListState, rows = 30)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Re-composes [hostSwitchable]'s fixture at [state] and settles. */
    private fun showScroll(state: LazyListState) {
        compose.runOnIdle { hostedListState = state }
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
     * release state is exactly the drag state. The scrollable consumes the
     * touch slop before it scrolls (Robolectric's is 16 dp, 48 px here), so
     * the finger travels [distanceDp] + slop for the list to scroll exactly
     * [distanceDp].
     */
    private fun dragUp(distanceDp: Float, startYFraction: Float = 0.6f) {
        compose.onRoot().performTouchInput {
            swipeWithVelocity(
                start = Offset(width / 2f, height * startYFraction),
                end = Offset(width / 2f, height * startYFraction - dp(distanceDp) - viewConfiguration.touchSlop),
                endVelocity = 0f,
            )
        }
    }

    // ------------------------------------------------------- geometry

    @Test
    fun largeTitleGeometryAtRest() {
        host()
        // topInset = 24 → navBottom = 68; the text box (natural 39 dp line +
        // 8 dp bottom inset) starts at 68 + 5 = 73, leading edge 16.
        val node = compose.onNodeWithTag("nav-large-title").fetchSemanticsNode()
        assertEquals(dp(16f), node.positionInRoot.x, dp(1f))
        assertEquals(dp(73f), node.positionInRoot.y, dp(2f))
    }

    @Test
    fun inlineTitleIsCentredOnTheFullWidthWhenCollapsed() {
        // 124 dp = the large title (52) + two rows (2 × 62) − 10, ticket 09's
        // pre-scroll: c = 52 with row 2 just under the bar, as in 1-off.png.
        host(listState = LazyListState(0, dp(124f).toInt()))
        val inline = compose.onNodeWithTag("nav-inline-title").fetchSemanticsNode()
        // Centre x = screen width / 2 (412 / 2), centre y = 24 + 22 = 46:
        // the 22.1 dp line box centred in the 44 dp nav row.
        assertEquals(dp(206f), inline.positionInRoot.x + inline.size.width / 2f, dp(1f))
        assertEquals(dp(46f), inline.positionInRoot.y + inline.size.height / 2f, dp(1f))
    }

    @Test
    fun trailingCircleIsAFortyFourSquareAtTrailingSixteen() {
        host()
        val more = compose.onNodeWithContentDescription("More").fetchSemanticsNode()
        assertEquals(dp(44f), more.size.width.toFloat(), 1f)
        assertEquals(dp(44f), more.size.height.toFloat(), 1f)
        assertEquals(dp(412f - 16f), more.positionInRoot.x + more.size.width, dp(1f))
        assertEquals(dp(46f), more.positionInRoot.y + more.size.height / 2f, dp(1f))
    }

    @Test
    fun backCircleGeometryAndTap() {
        var backTaps = 0
        host(showBack = true, onBack = { backTaps++ })
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
                ) { largeTitleFixtureRows(20) }
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
        // And bounded by the formula: width − 2·(max extent + 8), the larger
        // extent being 16 + the Edit capsule's measured width (≈ 58 dp:
        // "Edit" + 2 × 14 padding; a fixed 240 assumed ≥ 62 dp).
        val maxWidth = LargeTitleMetrics.inlineMaxWidth(
            width = dp(412f),
            leadingExtent = dp(16f) + back.size.width,
            trailingExtent = dp(16f) + edit.size.width,
            gap = dp(8f),
        )
        assertTrue(
            "inline title (${inline.size.width} px) wider than its max ($maxWidth px)",
            inline.size.width <= maxWidth + 1f,
        )
        // The title really is long enough to have been narrowed.
        assertTrue("inline title was not narrowed", inline.size.width >= maxWidth - dp(1f))
    }

    // ------------------------------------------- the collapse and its a11y

    @Test
    fun exactlyOneTitleIsExposedAtRestMidAndCollapsed() {
        // c = 0: the large title only.
        hostSwitchable(LazyListState())
        assertTrue(isHidden("nav-inline-title"))
        assertFalse(isHidden("nav-large-title"))
        assertEquals(1, visibleTitleHeadings("Sessions").size)

        // c = 35: still below the 0.5 flip (alpha 0.49) — the large title.
        showScroll(LazyListState(0, dp(35f).toInt()))
        assertTrue(isHidden("nav-inline-title"))
        assertFalse(isHidden("nav-large-title"))
        assertEquals(1, visibleTitleHeadings("Sessions").size)

        // c = 52 (scrolled exactly the 52 dp row): the inline title; the
        // large-title item now spans −52 … 0, inside the 68 dp top padding,
        // so it is still composed and must be hidden. (At the 124 dp
        // pre-scroll it spans −124 … −72, above the padding, and is not
        // composed at all.)
        showScroll(LazyListState(0, dp(52f).toInt()))
        assertFalse(isHidden("nav-inline-title"))
        assertTrue(isHidden("nav-large-title"))
        assertEquals(1, visibleTitleHeadings("Sessions").size)
    }

    @Test
    fun inlineTitleHiddenAtRestExposedWhenCollapsed() {
        hostSwitchable(LazyListState())
        assertTrue(isHidden("nav-inline-title"))
        showScroll(LazyListState(0, dp(60f).toInt()))
        assertFalse(isHidden("nav-inline-title"))
    }

    /**
     * The visible half of the fade, as pixel probes of the centre column
     * (clear of the ⋯ button): at rest the whole band above `navBottom` is
     * pure page background — the inline title draws at alpha 0 and no rows
     * run under the bar; collapsed (148 dp: row 2 spans 34 … 96) the band
     * carries ink — the inline title at alpha 1 and the row softened under
     * the bar. The scrim's blend and its 250 ms engage are ticket 09's own
     * tests; the goldens pin the combined look.
     */
    @Test
    fun bandIsEmptyAtRestAndCarriesInkWhenCollapsed() {
        val bg = Color(0xFFF3F3F5)
        val centerX = (dp(412f) / 2f).toInt()

        fun Color.isBackground(): Boolean =
            abs(red - bg.red) <= 2f / 255f && abs(green - bg.green) <= 2f / 255f && abs(blue - bg.blue) <= 2f / 255f

        hostSwitchable(LazyListState())
        val atRest = compose.onRoot().captureToImage().toPixelMap()
        for (y in 2 until dp(68f).toInt() step 2) {
            assertTrue("rest band pixel at y=$y is not background", atRest[centerX, y].isBackground())
        }

        showScroll(LazyListState(0, dp(148f).toInt()))
        val collapsed = compose.onRoot().captureToImage().toPixelMap()
        val ink = (dp(24f).toInt() until dp(68f).toInt()).count { !collapsed[centerX, it].isBackground() }
        assertTrue("no ink under the nav row when collapsed", ink > 0)
    }

    @Test
    fun navRowIsATraversalGroupReadBeforeTheContent() {
        host()
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

    /**
     * Round 45 (the Settings remove-wallpaper loop): only a scroll the user
     * dragged snaps. An accessibility/programmatic scroll — the semantics
     * ScrollBy that TalkBack and `performScrollToNode` issue — that rests at
     * 0 < c < L stays there; snapping it back to 0 undid it, and
     * `performScrollToNode` re-issued the same scroll forever. A later drag
     * still snaps.
     */
    @Test
    fun aSemanticsScrollInsideTheCollapseRangeStaysAndALaterDragStillSnaps() {
        val listState = LazyListState()
        host(listState = listState)
        compose.onNode(hasScrollBy).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dp(10f)) }
        compose.waitForIdle()
        assertEquals("a semantics scroll to c = 10 must not snap back", 10f, collapseOf(listState), 0.5f)

        // From c = 10, a 20 dp drag releases at c = 30 (past half): closed.
        dragUp(20f)
        compose.waitForIdle()
        assertEquals("the drag's release should snap to 52", 52f, collapseOf(listState), 0.5f)
    }

    /**
     * Round 49 evidence: a real drag on the fixture's list is observed on
     * `listState.interactionSource` as a `DragInteraction.Start`, and the
     * scaffold's snap gate saw the finger. Either failure message quotes the
     * scaffold's LTS-DIAG lines (interactions, scroll edges, gate state), so
     * CI shows which signal is missing.
     */
    @Test
    fun aDragIsObservedOnTheListsInteractionSourceAndOpensTheSnapGate() {
        // Round 50: the diagnostics are off by default; on for this test only
        // (before composing — the scaffold reads the switch when its effect starts).
        LargeTitleSnapDiagnostics.enabled = true
        try {
            dragObservedAndSnapped()
        } finally {
            LargeTitleSnapDiagnostics.enabled = false
        }
    }

    private fun dragObservedAndSnapped() {
        LargeTitleSnapDiagnostics.reset()
        val listState = LazyListState()
        val seen = mutableListOf<String>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LaunchedEffect(listState) {
                    listState.interactionSource.interactions.collect { seen += it::class.simpleName.orEmpty() }
                }
                LargeTitleFixtureScaffold(listState = listState, rows = 30)
            }
        }
        compose.waitForIdle()
        dragUp(20f)
        compose.waitForIdle()
        val diag = LargeTitleSnapDiagnostics.snapshot().joinToString("\n")
        assertTrue(
            "no DragInteraction.Start on listState.interactionSource; seen=$seen\n$diag",
            seen.contains("Start"),
        )
        assertTrue("the snap gate never saw the finger:\n$diag", diag.contains("pointer down -> userScroll=true"))
        assertEquals("c should have snapped to 0 (seen=$seen)\n$diag", 0f, collapseOf(listState), 0.5f)
    }

    private val hasScrollBy = SemanticsMatcher("has a ScrollBy action") {
        it.config.contains(SemanticsActions.ScrollBy)
    }

    // ------------------------------------------------------- hit testing

    @Test
    fun navRowBlocksTapsUnderItButTheExtensionPassesThrough() {
        var taps = 0
        // 148 dp = the large title + two rows + 34: row 2 spans 34 … 96, so
        // both tap points sit on the same clickable row — one inside the nav
        // row (blocked), one in the 16 dp extension below it (passed).
        host(listState = LazyListState(0, dp(148f).toInt()), onRowTap = { taps++ })
        compose.onRoot().performTouchInput { click(Offset(width / 2f, dp(40f))) }
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
        // c = 52 with the large-title item still composed in the top padding
        // (−52 … 0), so both titles exist; at 124 dp the item is not composed.
        val listState = LazyListState(0, dp(52f).toInt())
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LargeTitleFixtureScaffold(title = title, listState = listState)
            }
        }
        compose.waitForIdle()
        val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.runOnIdle { title = "Renamed" }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-inline-title").assert(hasText("Renamed"))
        compose.onNodeWithTag("nav-large-title").assert(hasText("Renamed"))
        compose.runOnIdle {
            assertEquals("the scroll position was reset", before, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
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
        // One setContent per test: the list position is state, keyed so each
        // position composes a fresh scaffold.
        var listState by mutableStateOf(LazyListState())
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                key(listState) {
                    LargeTitleScaffold(
                        title = "Sessions",
                        listState = listState,
                        topInset = 24.dp,
                        showBack = false,
                        onBack = {},
                        pinnedAccessory = { Box(Modifier.testTag("accessory").fillMaxSize()) },
                        pinnedAccessoryHeight = 40.dp,
                    ) { largeTitleFixtureRows(20) }
                }
            }
        }
        compose.waitForIdle()
        // At rest: under the large title (68 + 52); rows start below it.
        assertEquals(dp(120f), compose.onNodeWithTag("accessory").fetchSemanticsNode().positionInRoot.y, dp(1f))
        assertEquals(dp(160f), compose.onNodeWithTag("fixture-row-0").fetchSemanticsNode().positionInRoot.y, dp(1f))

        // Scrolled 100 dp (52 + 48 → item 1, offset 48): pinned at navBottom.
        compose.runOnIdle { listState = LazyListState(1, dp(48f).toInt()) }
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
                ) { largeTitleFixtureRows(20) }
            }
        }
        compose.waitForIdle()
        assertTrue("the caller's bottom padding was never consulted", callerPadding.bottomReads > 0)
        // The scaffold's own top padding (navBottom 68) is added on top of
        // the delegation; in Always mode the 52 dp large-title item comes
        // first, so row 0 starts at 68 + 52 (as in the accessory test).
        assertEquals(dp(120f), compose.onNodeWithTag("fixture-row-0").fetchSemanticsNode().positionInRoot.y, dp(1f))
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
