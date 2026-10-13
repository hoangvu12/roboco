package sh.roboco.android.shell

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.design.BottomChromeTopState
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalBottomChromeTop
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.LocalHazeState

/**
 * Ticket 30's shell behaviour (Robolectric, 402 × 874 dp at xxhdpi):
 * hidden-over-a-Session through the real [MainTabShell] (with a probe
 * `routeContent`), composition survival across tab switches / pushes /
 * a cancelled predictive back, the after-Search content fade on the frozen
 * clock, the predictive-back reveal, the chrome-top publication (734/804,
 * the toast anchor, the IME-lift subtraction) and
 * [LocalTransitionProgress]'s presence — the last group over a shell-like
 * fixture that pins `bottomInset = 22` so the worked dp values hold.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class ChromeVisibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The pushed demo Session: hides the chrome, counts taps. */
    private data class TestSessionRoute(val id: Int = 0) : NavRoute {
        override val hidesBottomChrome: Boolean get() = true
        override val paneTitle: String? get() = "Session"
    }

    /** Everything the probe screens and the shells hand back to the tests. */
    private class Probe {
        var chrome: ChromeVisibilityController? = null
        var minimize: TabBarMinimizeState? = null
        var sessionProgress: TransitionProgress? = null
        var insets: BottomChromeInsets? = null
        var navBottom: Dp = 0.dp
        var tappableBottom: Dp = 0.dp
        var sessionTaps = 0
        var accessoryTaps = 0
        var accessoryEnters = 0
        var accessoryDisposes = 0
        var toaster: Toaster? = null
    }

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------- fixtures

    private fun setShellContent(state: MainTabState, probe: Probe, chromeTop: BottomChromeTopState? = null) {
        compose.setContent {
            RobocoTheme {
                // A frozen glyph clock: the accessory's "2 working" spinner
                // otherwise keeps a withFrameNanos loop running forever and
                // Compose never idles (AppNotIdleException).
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    val holder = chromeTop
                    if (holder != null) {
                        CompositionLocalProvider(LocalBottomChromeTop provides holder) {
                            ShellWithProbe(state, probe)
                        }
                    } else {
                        ShellWithProbe(state, probe)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun ShellWithProbe(state: MainTabState, probe: Probe) {
        MainTabShell(
            state = state,
            counts = LiveCounts(working = 2, awaiting = 1),
            onNewSession = { probe.accessoryTaps++ },
            onChrome = { probe.chrome = it },
            routeContent = { entry -> ProbeScreen(entry, probe) },
        )
    }

    private fun setFixtureContent(
        tabs: MainTabState,
        probe: Probe,
        bottomInset: Dp = 22.dp,
        chromeTop: BottomChromeTopState? = null,
    ) {
        compose.setContent {
            RobocoTheme {
                ProvideGlyphClock(frozenAtNanos = 0L) { // see setShellContent
                    val holder = chromeTop
                    if (holder != null) {
                        CompositionLocalProvider(LocalBottomChromeTop provides holder) {
                            ChromeFixture(tabs, probe, bottomInset)
                        }
                    } else {
                        ChromeFixture(tabs, probe, bottomInset)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The shell-like fixture: the chrome wiring of [MainTabShell] (host
     * under one haze source, chrome sibling, [Modifier.chromeVisibility],
     * the chrome-top publication, the transition blocker) with 26/27's real
     * slots, a probe accessory slot and a pinned [bottomInset] (22 dp, the
     * worked table's B) so the dp values of the Spec hold.
     */
    @Composable
    private fun ChromeFixture(tabs: MainTabState, probe: Probe, bottomInset: Dp) {
        val minimize = rememberTabBarMinimizeState()
        val chrome = rememberChromeVisibilityController(tabs, minimize)
        val chromeTopState = LocalBottomChromeTop.current
        val density = LocalDensity.current
        val haze = rememberHazeState()
        val insets = BottomChromeInsets(bottomInset, bottomChromeContentPadding(bottomInset))
        probe.chrome = chrome
        probe.minimize = minimize

        LaunchedEffect(chrome, chromeTopState) {
            snapshotFlow {
                chrome.publishedChromeTop().let { if (it.isNaN()) Dp.Unspecified else it.dp }
            }.collect { chromeTopState.top = it }
        }
        DisposableEffect(chromeTopState) {
            onDispose { chromeTopState.top = Dp.Unspecified }
        }

        CompositionLocalProvider(
            LocalTabBarMinimize provides minimize,
            LocalBottomChrome provides insets,
        ) {
            Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                Box(Modifier.fillMaxSize().hazeSource(haze)) {
                    val tab = tabs.selected
                    key(tab) {
                        NavStackHost(tabs.stack(tab), active = true, Modifier.fillMaxSize()) { entry ->
                            ProvideTransitionProgress(tabs.stack(tab), entry) {
                                ProbeScreen(entry, probe)
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalHazeState provides haze) {
                    TabChromeLayout(
                        progress = { minimize.progress.value },
                        bottomInset = bottomInset,
                        hasAccessory = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .onPlaced { chrome.layoutTopInRoot = it.positionInRoot().y }
                            .chromeVisibility(chrome),
                        onRects = { rects -> chrome.updateChromeTop(rects.chromeTop, density) },
                        pill = {
                            GlassTabPill(
                                selected = TabId.Sessions,
                                onTabTap = {},
                                minimizeProgress = { minimize.progress.value },
                                minimized = minimize.minimized,
                                onRestore = {},
                                expandedWidth = tabPillExpandedWidth(402f).dp,
                            )
                        },
                        search = { GlassSearchButton(TabId.Sessions, onTabTap = {}) },
                        accessory = {
                            DisposableEffect(Unit) {
                                probe.accessoryEnters++
                                onDispose { probe.accessoryDisposes++ }
                            }
                            NewSessionAccessory(
                                counts = LiveCounts(working = 2, awaiting = 1),
                                onTap = { probe.accessoryTaps++ },
                                inline = minimize.minimized,
                                labelAlpha = { 1f - minimize.progress.value.coerceIn(0f, 1f) },
                            )
                        },
                    )
                }
                ChromeTransitionBlocker(chrome, Modifier.matchParentSize())
            }
        }
    }

    /** The probe screen: one line per capture, a tap-counting Session. */
    @Composable
    private fun ProbeScreen(entry: NavEntry, probe: Probe) {
        val density = LocalDensity.current
        probe.navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        probe.tappableBottom = with(density) { WindowInsets.tappableElement.getBottom(density).toDp() }
        probe.insets = LocalBottomChrome.current
        probe.minimize = LocalTabBarMinimize.current
        if (entry.route is TestSessionRoute) {
            probe.sessionProgress = LocalTransitionProgress.current
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { probe.sessionTaps++ },
            )
        } else {
            Box(Modifier.fillMaxSize())
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * The ticket's helper: the tag's nodes are absent from the semantics
     * tree, or every node that stayed (an unplaced node keeps its
     * semantics on some Compose versions) fails `isDisplayed()`.
     */
    private fun assertHiddenOrAbsent(tag: String) {
        val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        for (index in 0 until count) {
            assertFalse("$tag node $index must not be displayed while hidden", nodes.get(index).isDisplayed())
        }
    }

    /** Starts a predictive back gesture and progresses it to [progress]. */
    private fun startBackGesture(progress: Float) {
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, progress, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
    }

    private fun accessoryBoundsCenter(): Offset =
        compose.onNodeWithTag("new-session", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center

    private fun accessoryTopDp(): Float {
        val topPx = compose.onNodeWithTag("new-session", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        return with(compose.density) { topPx.toDp() }.value
    }

    private fun alphaOf(probe: Probe): Float = probe.chrome!!.accessoryContentAlpha()

    /** Runs frames until the search fade has snapped to 0 (its first frame). */
    private fun advanceUntilTheFadeSnapped(probe: Probe) {
        var frames = 0
        while (frames < 12 && alphaOf(probe) != 0f) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        assertEquals("the search fade must snap to 0 within a few frames", 0f, alphaOf(probe), 1e-4f)
    }

    // ------------------------------------------------ hidden over a Session

    @Test
    fun aSessionOnTopHidesTheWholeGroupAndTapsReachTheSession() {
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        val chromeTop = BottomChromeTopState()
        setShellContent(state, probe, chromeTop)
        val accessoryCenter = accessoryBoundsCenter()

        compose.runOnUiThread { state.stack(TabId.Sessions).push(TestSessionRoute(), animated = false) }
        compose.waitForIdle()

        assertHiddenOrAbsent("new-session")
        assertHiddenOrAbsent("tab-pill")
        assertHiddenOrAbsent("tab-search")
        assertFalse("the chrome top must be Unspecified over a Session", chromeTop.top.isSpecified)

        // A tap at the old accessory rect reaches the session underneath.
        assertEquals(0, probe.sessionTaps)
        compose.onRoot().performTouchInput { click(accessoryCenter) }
        compose.waitForIdle()
        assertEquals(1, probe.sessionTaps)
    }

    @Test
    fun aHidingRouteOnTheSearchAndSettingsStacksHidesTheGroupToo() {
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        val chromeTop = BottomChromeTopState()
        setShellContent(state, probe, chromeTop)

        compose.runOnUiThread {
            state.select(TabId.Settings)
            state.stack(TabId.Settings).push(TestSessionRoute(), animated = false)
        }
        compose.waitForIdle()
        assertHiddenOrAbsent("new-session")
        assertFalse(chromeTop.top.isSpecified)

        compose.runOnUiThread {
            state.stack(TabId.Settings).pop()
            state.select(TabId.Search)
            state.stack(TabId.Search).push(TestSessionRoute(), animated = false)
        }
        compose.waitForIdle()
        assertHiddenOrAbsent("new-session")
        assertFalse(chromeTop.top.isSpecified)
    }

    // -------------------------------------------------- composition survival

    @Test
    fun theAccessorySlotNeverLeavesComposition() {
        val tabs = MainTabState(TabId.Sessions)
        val probe = Probe()
        setFixtureContent(tabs, probe)
        assertEquals(1, probe.accessoryEnters)
        assertEquals(0, probe.accessoryDisposes)

        compose.runOnUiThread { tabs.select(TabId.Settings) }
        compose.waitForIdle()
        compose.runOnUiThread { tabs.select(TabId.Search) }
        compose.waitForIdle()
        compose.runOnUiThread { tabs.select(TabId.Sessions) }
        compose.waitForIdle()

        val stack = tabs.stack(TabId.Sessions)
        compose.runOnUiThread { stack.push(TestSessionRoute()) }
        compose.waitForIdle()
        compose.runOnUiThread { stack.pop() }
        compose.waitForIdle()

        startBackGesture(0.4f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()

        assertEquals("the accessory slot must survive everything (#577)", 1, probe.accessoryEnters)
        assertEquals(0, probe.accessoryDisposes)
    }

    // -------------------------------------------------------- the search fade

    @Test
    fun leavingSearchFadesTheContentBackInOver250msAfter120ms() {
        ReduceMotion.debugOverride = false
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        compose.mainClock.autoAdvance = false
        setShellContent(state, probe)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.runOnUiThread { state.select(TabId.Search) }
        compose.mainClock.advanceTimeBy(1000) // the morph settles
        compose.waitForIdle()
        assertEquals(1f, alphaOf(probe), 1e-4f)

        compose.runOnUiThread { state.select(TabId.Sessions) }
        advanceUntilTheFadeSnapped(probe)
        // The tween's first frame is the next one: t = 0 from here.
        compose.mainClock.advanceTimeByFrame()

        // advanceTimeBy rounds up to whole 16 ms frames: t = 112, inside the
        // 120 ms delay.
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0f, alphaOf(probe), 1e-4f)

        // The accessory stays tappable during the fade (.allowUserInteraction).
        // The click's down → move → up advances the clock one 16 ms event
        // period (AndroidInputDispatcher.advanceClockTime): t = 128.
        compose.onNodeWithTag("new-session", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, probe.accessoryTaps)

        compose.mainClock.advanceTimeBy(112) // t = 240: EaseOut at x = 0.48 (≈ 0.669)
        assertEquals(Motion.EaseOut.transform(0.48f), alphaOf(probe), 0.02f)

        compose.mainClock.advanceTimeBy(135) // t = 384: done (≥ 370)
        assertEquals(1f, alphaOf(probe), 1e-4f)
    }

    @Test
    fun reducedMotionSkipsTheFade() {
        ReduceMotion.debugOverride = true
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        compose.mainClock.autoAdvance = false
        setShellContent(state, probe)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.runOnUiThread { state.select(TabId.Search) }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnUiThread { state.select(TabId.Sessions) }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1f, alphaOf(probe), 1e-4f)
        compose.mainClock.advanceTimeBy(280)
        assertEquals(1f, alphaOf(probe), 1e-4f)
    }

    @Test
    fun aNonSearchTabChangeNeverTouchesTheContentAlpha() {
        ReduceMotion.debugOverride = false
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        compose.mainClock.autoAdvance = false
        setShellContent(state, probe)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.runOnUiThread { state.select(TabId.Settings) }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(1f, alphaOf(probe), 1e-4f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(1f, alphaOf(probe), 1e-4f)
    }

    // -------------------------------------------------------- predictive back

    @Test
    fun aBackGestureFromASessionRevealsTheChromeWithTheProgress() {
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        setShellContent(state, probe)
        compose.runOnUiThread { state.stack(TabId.Sessions).push(TestSessionRoute()) }
        compose.waitForIdle()

        startBackGesture(0.5f)
        val chrome = probe.chrome!!
        val frame = chrome.frame()
        assertTrue(frame.placed)
        assertEquals(0.5f, chrome.accessoryContentAlpha(), 0.01f)
        // clipRight = aboveTx = 0.5 × window width (402 dp × 3 px).
        assertEquals(603f, frame.clipRight, 1f)
        assertEquals(0f, probe.minimize!!.progress.value, 1e-3f)
        // The accessory is laid out at its final place (the "flew in from
        // the top" regression): its top is past 60 % of the window height.
        assertTrue("accessory top ${accessoryTopDp()} dp must sit past 0.6 × 874", accessoryTopDp() > 0.6f * 874f)
        compose.onNodeWithTag("new-session", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aCancelledBackKeepsTheChromeHiddenAndSnapsTheAlphaBackToOne() {
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        val chromeTop = BottomChromeTopState()
        setShellContent(state, probe, chromeTop)
        compose.runOnUiThread { state.stack(TabId.Sessions).push(TestSessionRoute()) }
        compose.waitForIdle()

        startBackGesture(0.5f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()

        assertHiddenOrAbsent("new-session")
        assertFalse(chromeTop.top.isSpecified)
        assertEquals(1f, alphaOf(probe), 1e-4f)
        // The session stays on screen (24's cancelled-pop contract).
        assertEquals(2, state.stack(TabId.Sessions).entries.size)
        assertFalse(state.stack(TabId.Sessions).top.isClosed)
    }

    @Test
    fun aCommittedBackRestoresTheChromeAndPublishesItsTop() {
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        val chromeTop = BottomChromeTopState()
        setShellContent(state, probe, chromeTop)
        compose.runOnUiThread { state.stack(TabId.Sessions).push(TestSessionRoute()) }
        compose.waitForIdle()

        startBackGesture(0.5f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag("new-session", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1f, alphaOf(probe), 1e-4f)
        assertEquals(accessoryTopDp(), chromeTop.top.value, 0.5f)
    }

    // ------------------------------------------------------- the chrome top

    @Test
    fun theChromeTopIs734ExpandedAnd804Minimized() {
        val tabs = MainTabState(TabId.Sessions)
        val probe = Probe()
        val chromeTop = BottomChromeTopState()
        setFixtureContent(tabs, probe, bottomInset = 22.dp, chromeTop = chromeTop)
        assertEquals(734f, chromeTop.top.value, 0.5f)

        compose.runOnUiThread { probe.minimize!!.minimize(animate = false) }
        compose.waitForIdle()
        assertEquals(804f, chromeTop.top.value, 0.5f)
    }

    @Test
    fun aToastSitsTwelveDpAboveTheAccessory() {
        val tabs = MainTabState(TabId.Sessions)
        val probe = Probe()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                ToastHost {
                    ChromeFixture(tabs, probe, bottomInset = 22.dp)
                    probe.toaster = LocalToaster.current
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.runOnUiThread { probe.toaster!!.show("Archived", "Undo") {} }
        compose.mainClock.advanceTimeBy(1000) // the rise settles, the hold (4 s) has not ended
        compose.waitForIdle()

        val bottomPx = compose.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot.bottom
        val bottomDp = with(compose.density) { bottomPx.toDp() }.value
        assertEquals(722f, bottomDp, 0.5f)
    }

    @Test
    fun publishedChromeTopSubtractsTheImeLiftPastTheAccessory() {
        val controller = ChromeVisibilityController(
            MainTabState(TabId.Sessions),
            null,
            CoroutineScope(Dispatchers.Unconfined),
        )
        controller.chromeTopInRoot = 734f
        assertEquals(734f, controller.publishedChromeTop(), 1e-3f)
        controller.searchImeLift = { 40f }
        assertEquals(734f, controller.publishedChromeTop(), 1e-3f)
        controller.searchImeLift = { 286f }
        assertEquals(504f, controller.publishedChromeTop(), 1e-3f)
    }

    // ---------------------------------------------------- the bottom insets

    @Test
    fun theShellProvidesTheBottomChromeInsets() {
        val state = MainTabState(TabId.Sessions)
        val probe = Probe()
        setShellContent(state, probe)
        val insets = probe.insets
        assertNotNull("LocalBottomChrome must be provided", insets)
        assertEquals(tabBarBottomGap(probe.navBottom, probe.tappableBottom), insets!!.barBottom)
        assertEquals(bottomChromeContentPadding(insets.barBottom), insets.contentPadding)
    }

    // -------------------------------------------------- transition progress

    @Test
    fun aFrozenPushAtHalfReadsHalfPresenceAndAnIdleSessionReadsOne() {
        val frozen = MainTabState(TabId.Sessions).apply {
            val s = stack(TabId.Sessions)
            s.holdHostlessOps()
            s.push(TestSessionRoute())
            s.freeze(0.5f)
        }
        val probe = Probe()
        // One setContent per test (an Android compose rule refuses a
        // second): the fixture swaps from the frozen to the idle shell in
        // place, keyed so the second one starts from fresh state.
        var fixture by mutableStateOf(frozen to probe)
        compose.setContent {
            RobocoTheme {
                ProvideGlyphClock(frozenAtNanos = 0L) { // see setShellContent
                    val (tabs, p) = fixture
                    key(tabs) { ChromeFixture(tabs, p, 22.dp) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0.5f, probe.sessionProgress?.presence ?: -1f, 1e-3f)

        val idle = MainTabState(TabId.Sessions).apply { stack(TabId.Sessions).push(TestSessionRoute()) }
        val idleProbe = Probe()
        compose.runOnUiThread { fixture = idle to idleProbe }
        compose.waitForIdle()
        assertEquals(1f, idleProbe.sessionProgress?.presence ?: -1f, 1e-3f)
        assertEquals(0f, idleProbe.sessionProgress?.chromeVisibility ?: 1f, 1e-3f)
        assertFalse(idleProbe.sessionProgress?.isInteractive ?: true)
    }

    @Test
    fun aPushedSessionReadsZeroPresenceOnItsFirstFrame() {
        val tabs = MainTabState(TabId.Sessions)
        val probe = Probe()
        compose.mainClock.autoAdvance = false
        setFixtureContent(tabs, probe)
        compose.runOnUiThread { tabs.stack(TabId.Sessions).push(TestSessionRoute()) }
        compose.mainClock.advanceTimeByFrame()
        val firstFrame = probe.sessionProgress?.presence ?: -1f
        assertTrue("presence must read ~0 on the first frame, was $firstFrame", firstFrame <= 0.02f)
        compose.mainClock.advanceTimeBy(300)
        assertTrue((probe.sessionProgress?.presence ?: 0f) > 0.5f)
    }

    @Test
    fun aPopGestureReadsOneMinusTheProgressAndACancelRestoresOne() {
        val tabs = MainTabState(TabId.Sessions)
        val probe = Probe()
        setFixtureContent(tabs, probe)
        compose.runOnUiThread { tabs.stack(TabId.Sessions).push(TestSessionRoute()) }
        compose.waitForIdle()
        assertTrue(probe.sessionProgress?.isInteractive == false)

        startBackGesture(0.4f)
        assertEquals(0.6f, probe.sessionProgress?.presence ?: -1f, 0.01f)
        assertTrue(probe.sessionProgress?.isInteractive == true)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(1f, probe.sessionProgress?.presence ?: -1f, 1e-3f)
    }

    // ------------------------------------------------------- blocked chrome

    @Test
    fun chromeClicksAreBlockedWhileATransitionRuns() {
        val tabs = MainTabState(TabId.Sessions)
        val probe = Probe()
        tabs.stack(TabId.Sessions).apply {
            holdHostlessOps()
            push(PlaceholderDetailRoute(TabId.Sessions, 5)) // both ends show: Fixed, fully visible
            freeze(0.5f)
        }
        setFixtureContent(tabs, probe)
        // Fixed (both ends show): the chrome is fully visible — and blocked.
        compose.onNodeWithTag("new-session", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("new-session", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("a click during a transition must do nothing", 0, probe.accessoryTaps)
    }
}
