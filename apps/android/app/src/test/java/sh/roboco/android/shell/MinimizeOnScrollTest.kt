package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.glass.GlassMenuHost
import kotlin.math.abs

/**
 * The minimize detector and the chrome layout (android-ui-parity #28,
 * Robolectric, 402 × 874 dp at xxhdpi with the zero insets Robolectric
 * reports): a 100-row `LazyColumn` under [Modifier.minimizeOnScroll] plus
 * [TabChromeLayout] with tagged placeholder slots —
 *
 *  - one slow swipe up minimizes; after settling, the progress key reads
 *    1 and the slots sit at the worked-table minimized geometry;
 *  - a swipe down restores; the accessory sits 8 dp above the 62 dp pill;
 *  - a tap on `tab-bar-minimized` restores without scrolling the list or
 *    switching the tab;
 *  - a pull at the top (nothing consumed) leaves the chrome expanded and
 *    the list still;
 *  - `enabled = false` ignores swipes;
 *  - the morph follows `Motion.TabBarMinimize` (0.663 at exactly 100 ms;
 *    the window is widened for frame alignment) and settles by 800 ms;
 *  - Reduce Motion snaps it in one frame;
 *  - an expand mid-minimize reverses from the current value and never
 *    jumps back to 1;
 *  - the detector never consumes scroll (same fling distance ± 1 px with
 *    and without it);
 *  - the accessory slot records `inline` at the first frame and its label
 *    alpha follows `1 − p`;
 *  - and with the real [MainTabShell]: a chrome-hiding route disables the
 *    detector and a committed push/pop restores, and a tab switch while
 *    minimized expands with the spring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class MinimizeOnScrollTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------- fixtures

    /** The harness state, written from composition. */
    private var state: TabBarMinimizeState? = null
    private var list: LazyListState? = null

    /** The detector's gate, flipped from the test thread. */
    private var enabled by mutableStateOf(true)

    /**
     * Whether the list sits under the detector, and the list's state: both
     * snapshot state so [theDetectorNeverConsumesScroll] can swap to a bare,
     * fresh list in place (an Android compose rule allows one `setContent`
     * per test).
     */
    private var detectorInstalled by mutableStateOf(true)
    private var listState by mutableStateOf(LazyListState())

    /** The selected tab in the real-slot harness. */
    private var selectedTab by mutableStateOf(TabId.Sessions)

    /** The accessory slot's recorded inputs (the ticket's inline/labelAlpha). */
    private var inlineRecorded by mutableStateOf(false)
    private var labelAlphaRecorded by mutableStateOf(1f)

    /**
     * The list (100 rows × 56 dp) with the detector around it, and the
     * chrome above: tagged placeholder slots, or the real pill / search /
     * accessory. [installDetector] keeps the identical list bare, for the
     * never-consumes comparison.
     */
    private fun placeChrome(real: Boolean = false, installDetector: Boolean = true) {
        detectorInstalled = installDetector
        listState = LazyListState()
        list = listState
        compose.setContent {
            RobocoTheme {
                // A frozen glyph clock: the real accessory's "2 working"
                // spinner otherwise runs a withFrameNanos loop forever and
                // Compose never idles (AppNotIdleException).
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    val palette = RobocoTheme.palette
                    val typeScale = LocalTypeScale.current
                    val minimize = rememberTabBarMinimizeState()
                    state = minimize
                    val lazyListState = listState
                    val content: @Composable BoxScope.() -> Unit = {
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("the-list"),
                            state = lazyListState,
                        ) {
                            items(100) { index ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("row-$index"),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    BasicText(
                                        text = "Row ${index + 1}",
                                        style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.text),
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        if (detectorInstalled) {
                            Box(Modifier.fillMaxSize().minimizeOnScroll(minimize) { enabled }, content = content)
                        } else {
                            Box(Modifier.fillMaxSize(), content = content)
                        }
                        if (real) {
                            TabChromeLayout(
                                progress = { minimize.progress.value },
                                bottomInset = 21.dp,
                                hasAccessory = true,
                                modifier = Modifier.fillMaxSize().selectableGroup(),
                                pill = {
                                    GlassTabPill(
                                        selected = selectedTab,
                                        onTabTap = { selectedTab = it },
                                        minimizeProgress = { minimize.progress.value },
                                        minimized = minimize.minimized,
                                        onRestore = { minimize.expand() },
                                        expandedWidth = tabPillExpandedWidth(402f).dp,
                                    )
                                },
                                search = { GlassSearchButton(selectedTab, onTabTap = { selectedTab = it }) },
                                accessory = {
                                    // The shell's wiring (#28): inline is the target,
                                    // the label alpha follows 1 − p̂.
                                    NewSessionAccessory(
                                        counts = LiveCounts(working = 2, awaiting = 1),
                                        onTap = {},
                                        inline = minimize.minimized,
                                        labelAlpha = { 1f - minimize.progress.value.coerceIn(0f, 1f) },
                                    )
                                },
                            )
                        } else {
                            TabChromeLayout(
                                progress = { minimize.progress.value },
                                bottomInset = 21.dp,
                                hasAccessory = true,
                                modifier = Modifier.fillMaxSize().testTag("tab-chrome"),
                                pill = { Box(Modifier.fillMaxSize().testTag("slot-pill")) },
                                search = { Box(Modifier.fillMaxSize().testTag("slot-search")) },
                                accessory = {
                                    // Records the two inputs this ticket passes to
                                    // 27's composable: inline (the target, first
                                    // frame) and the label alpha (per frame).
                                    val inlineNow = minimize.minimized
                                    SideEffect { inlineRecorded = inlineNow }
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .testTag("slot-accessory")
                                            .graphicsLayer {
                                                labelAlphaRecorded = 1f - minimize.progress.value.coerceIn(0f, 1f)
                                            },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The real shell over the placeholder roots. */
    private fun placeShell(shellState: MainTabState) {
        compose.setContent {
            RobocoTheme {
                // Ticket 46: the Sessions root is the real screen now — its
                // ⋯ needs a GlassMenuHost and its loading spinner a frozen
                // glyph clock.
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    GlassMenuHost { MainTabShell(state = shellState) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun swipeTheListUp() {
        compose.onNodeWithTag("the-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    /** True when the morph has finished (not running, at its target). */
    private fun settle(): Boolean {
        val s = requireNotNull(state)
        return !s.progress.isRunning && s.progress.value == if (s.minimized) 1f else 0f
    }

    /** The pill slot's width in dp (xxhdpi: / 3). */
    private fun widthDp(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.width / 3f

    // -------------------------------------------------- swipe → minimized

    @Test
    fun swipeUpMinimizesAndTheGeometrySettles() {
        placeChrome()
        swipeTheListUp()
        assertTrue(requireNotNull(state).minimized)
        compose.waitUntil(5_000) { settle() }
        assertEquals(1f, compose.onNodeWithTag("tab-chrome").fetchSemanticsNode().config[TabMinimizeProgressKey], 1e-4f)
        // The pill circle: 48 × 48 at left 21, bottom 853 = 874 − 21.
        compose.onNodeWithTag("slot-pill")
            .assertPositionInRootIsEqualTo(21.dp, 805.dp)
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
        // The search circle: 48 × 48, right edge at width − 21 (x 333).
        compose.onNodeWithTag("slot-search")
            .assertPositionInRootIsEqualTo(333.dp, 805.dp)
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
        // The inline accessory: 48 dp high, rowW − 112 = 248 wide, at left
        // 77, bottom-aligned with the circles.
        compose.onNodeWithTag("slot-accessory")
            .assertPositionInRootIsEqualTo(77.dp, 805.dp)
            .assertWidthIsEqualTo(248.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun swipeDownRestoresTheExpandedChrome() {
        placeChrome()
        swipeTheListUp()
        compose.waitUntil(5_000) { settle() }
        compose.onNodeWithTag("the-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { settle() }
        assertFalse(requireNotNull(state).minimized)
        assertEquals(0f, compose.onNodeWithTag("tab-chrome").fetchSemanticsNode().config[TabMinimizeProgressKey], 1e-4f)
        // The accessory is back 8 dp above the 62 dp pill.
        compose.onNodeWithTag("slot-accessory")
            .assertPositionInRootIsEqualTo(21.dp, 735.dp)
            .assertWidthIsEqualTo(360.dp)
            .assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("slot-pill")
            .assertPositionInRootIsEqualTo(21.dp, 791.dp)
            .assertWidthIsEqualTo(290.dp)
            .assertHeightIsEqualTo(62.dp)
    }

    // ------------------------------------------------------- the restore tap

    @Test
    fun tappingTheMinimizedCircleRestoresWithoutScrollingOrSwitching() {
        placeChrome(real = true)
        swipeTheListUp()
        compose.waitUntil(5_000) { settle() }
        val first = requireNotNull(list).firstVisibleItemIndex
        val offset = requireNotNull(list).firstVisibleItemScrollOffset
        compose.onNodeWithTag("tab-bar-minimized").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { settle() }
        assertFalse(requireNotNull(state).minimized)
        // The list did not scroll and the selected tab did not change.
        assertEquals(first, requireNotNull(list).firstVisibleItemIndex)
        assertEquals(offset, requireNotNull(list).firstVisibleItemScrollOffset)
        assertEquals(TabId.Sessions, selectedTab)
        // The expanded pill's per-item semantics are back.
        compose.onNodeWithTag("tab-sessions").assertExists()
    }

    // -------------------------------------------------------- at the top

    @Test
    fun pullingDownAtTheTopKeepsTheChromeExpandedAndTheListStill() {
        placeChrome()
        // The list starts at its top, the chrome expanded: a downward pull
        // leaves leftover the list cannot consume — nothing we may act on,
        // and nothing we may consume.
        compose.onNodeWithTag("the-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFalse(requireNotNull(state).minimized)
        assertEquals(0, requireNotNull(list).firstVisibleItemIndex)
        assertEquals(0, requireNotNull(list).firstVisibleItemScrollOffset)
    }

    @Test
    fun aDisabledDetectorIgnoresSwipes() {
        placeChrome()
        enabled = false
        swipeTheListUp()
        assertFalse(requireNotNull(state).minimized)
    }

    // -------------------------------------------------------- the spring

    @Test
    fun theMorphFollowsTheTabBarMinimizeSpring() {
        placeChrome()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { requireNotNull(state).minimize() }
        // 100 ms in: the spring sits at 0.663 exactly (the ticket's
        // checkpoint); one frame of launch alignment brings it to ~0.53,
        // so the window is [0.50, 0.80] — the point is mid-flight, not
        // settled, not at the start.
        compose.mainClock.advanceTimeBy(100)
        val p = requireNotNull(state).progress.value
        assertTrue("progress at 100 ms: $p", p in 0.50f..0.80f)
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertTrue(!requireNotNull(state).progress.isRunning)
        assertEquals(1f, requireNotNull(state).progress.value, 1e-4f)
    }

    @Test
    fun reduceMotionSnapsTheMorph() {
        placeChrome()
        ReduceMotion.debugOverride = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { requireNotNull(state).minimize() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, requireNotNull(state).progress.value, 1e-4f)
        assertTrue(requireNotNull(state).minimized)
    }

    @Test
    fun anExpandMidMinimizeReversesFromTheCurrentValue() {
        placeChrome()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { requireNotNull(state).minimize() }
        compose.mainClock.advanceTimeBy(100)
        val mid = requireNotNull(state).progress.value
        assertTrue("mid-flight progress: $mid", mid > 0.3f && mid < 0.95f)
        compose.runOnUiThread { requireNotNull(state).expand() }
        var maxSeen = mid
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            maxSeen = maxOf(maxSeen, requireNotNull(state).progress.value)
        }
        // It fell from the current value; it never jumped back to 1 first.
        assertTrue("progress peaked at $maxSeen", maxSeen < 0.95f)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(0f, requireNotNull(state).progress.value, 1e-4f)
        assertFalse(requireNotNull(state).minimized)
    }

    // -------------------------------------------------- never consumes

    @Test
    fun theDetectorNeverConsumesScroll() {
        placeChrome()
        swipeTheListUp()
        val withDetector = scrollPositionPx()
        // The identical list, fresh and bare, swapped in place.
        compose.runOnUiThread {
            listState = LazyListState()
            list = listState
            detectorInstalled = false
        }
        compose.waitForIdle()
        swipeTheListUp()
        val withoutDetector = scrollPositionPx()
        assertTrue(
            "the same swipe scrolled to $withDetector with the detector, " +
                "$withoutDetector without it",
            abs(withDetector - withoutDetector) <= 1f,
        )
    }

    /** The list's scroll position in px (56 dp rows at xxhdpi: 168 px). */
    private fun scrollPositionPx(): Float {
        val lazy = requireNotNull(list)
        return (lazy.firstVisibleItemIndex * 168 + lazy.firstVisibleItemScrollOffset).toFloat()
    }

    // ------------------------------------------- the accessory's inputs

    @Test
    fun theAccessoryInputsFlipAtTheStartAndFadeWithTheMorph() {
        placeChrome()
        compose.mainClock.autoAdvance = false
        assertFalse(inlineRecorded)
        compose.runOnUiThread { requireNotNull(state).minimize() }
        // The first frame: inline (the target) has already flipped.
        compose.mainClock.advanceTimeByFrame()
        assertTrue(inlineRecorded)
        compose.mainClock.advanceTimeBy(100)
        // 1 − p̂ at 100 ms: ≈ 0.34 at the exact checkpoint, ≈ 0.47 with a
        // frame of launch alignment — the ticket's "≈ 0.49".
        val alpha = labelAlphaRecorded
        assertTrue("label alpha at 100 ms: $alpha", alpha in 0.20f..0.50f)
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(0f, labelAlphaRecorded, 0.001f)
    }

    // ------------------------------------------------ the real shell

    @Test
    fun aChromeHidingRouteDisablesTheDetectorAndPopShowsExpandedChrome() {
        val shellState = MainTabState(TabId.Sessions)
        placeShell(shellState)
        // Ticket 46: the real Sessions root is empty under Robolectric, so
        // the minimize runs over the Settings placeholder's list (the
        // detector is the shell's, tab-agnostic).
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-root-more").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("tab-bar-minimized").assertExists()
        // A pushed chrome-hiding route (a Session): the committed push
        // restores the bar — snapped, not animated — and the detector is
        // disabled while it is on top.
        compose.runOnUiThread { shellState.stack(TabId.Settings).push(HidingRoute) }
        compose.waitUntil(5_000) { shellState.stack(TabId.Settings).top.route.hidesBottomChrome }
        compose.waitForIdle()
        compose.onNodeWithTag("tab-bar-minimized").assertDoesNotExist()
        // Ticket 30 leaves the whole chrome group unplaced over a hiding
        // route (no placed bounds, so assertWidthIsEqualTo reads
        // Dp.Unspecified), but it is always measured (#577): the pill's
        // measured size is the expanded 290.
        assertEquals(290f, widthDp("tab-pill"), 0.5f)
        // Scrolling the hiding route's blank screen cannot minimize.
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("tab-bar-minimized").assertDoesNotExist()
        // Popping back shows the expanded chrome over the root list.
        // A hosted pop only commits once NavStackHost's driver (a
        // LaunchedEffect keyed on the transition) has animated it, and
        // waitUntil only advances frames — it never drains the posted apply
        // notification, so without the explicit send the Recomposer never
        // sees the transition and the pop never commits.
        compose.runOnUiThread {
            shellState.stack(TabId.Settings).pop()
            Snapshot.sendApplyNotifications()
        }
        compose.waitUntil(5_000) { !shellState.stack(TabId.Settings).top.route.hidesBottomChrome }
        compose.waitForIdle()
        compose.onNodeWithTag("tab-root-more").assertExists()
        compose.onNodeWithTag("tab-bar-minimized").assertDoesNotExist()
        compose.onNodeWithTag("tab-pill").assertWidthIsEqualTo(290.dp)
    }

    @Test
    fun switchingTabWhileMinimizedExpandsWithTheSpring() {
        val shellState = MainTabState(TabId.Sessions)
        placeShell(shellState)
        // Ticket 46: minimize over the Settings placeholder's list, then
        // switch to the (real) Sessions tab while minimized.
        compose.onNodeWithTag("tab-more").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-root-more").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("tab-bar-minimized").assertExists()
        compose.mainClock.autoAdvance = false
        // A tab switch while minimized: the expand animates.
        compose.runOnUiThread { shellState.select(TabId.Sessions) }
        // The write was made outside composition on the (paused) main
        // looper: hand it to the Recomposer before advancing the frozen clock.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(150)
        val width = widthDp("tab-pill")
        assertTrue("mid-expand pill width: $width", width > 48.5f && width < 289.5f)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { widthDp("tab-pill") == 290f }
        compose.onNodeWithTag("tab-root-sessions").assertExists()
    }

    /** A route that hides the bottom chrome, like a Session (76). */
    private object HidingRoute : NavRoute {
        override val hidesBottomChrome: Boolean get() = true
    }
}
