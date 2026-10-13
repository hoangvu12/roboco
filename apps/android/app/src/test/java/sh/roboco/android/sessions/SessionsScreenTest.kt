package sh.roboco.android.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.chrisbanes.haze.rememberHazeState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.WallpaperArtwork
import sh.roboco.android.design.WallpaperHero
import sh.roboco.android.design.WallpaperRender
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.wallpaperFixture
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.LocalTabId
import sh.roboco.android.shell.LocalTabScrollToTop
import sh.roboco.android.shell.ShellRouteContent
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import sh.roboco.android.shell.TabScrollToTop

/**
 * The Sessions tab root's behaviour (android-ui-parity #46): the ⋯ flow,
 * the header long-press menus (the port of `iosui:SessionFlowTests.swift:24-40`),
 * the hero frame and its scroll fade, the fold wiring and the
 * recomposition budget — Robolectric + `createComposeRule` at
 * `w402dp-h874dp-xxhdpi` (1 dp = 3 px; Robolectric reports no insets, so
 * `topInset` is 0 and the nav row is 0…44).
 *
 * The host mirrors ticket 35's mount order with the menus inside:
 * `RobocoTheme { ContextMenuHost { GlassMenuHost { NavStackHost { … } } } }`
 * (16 outermost), over a real `NavStack(TabRootRoute(TabId.Sessions))`:
 * pushes land on this tab's stack and render through
 * [ShellRouteContent] (45's folder pages). The glyph clock is frozen (the
 * loading state's spinner), [LocalFeedback] is a recorder (the long-press
 * haptic), and the actions are [ScreenRecorder] — a recorder whose pushes
 * are real, so the iOS UI test's flow (long-press Pinned → Open → the
 * "Pinned" bar appears) runs exactly as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class SessionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ the fakes

    /**
     * The recorder: every call logs one line; the pushes land on the real
     * stack (non-animated, so the entry is committed at click time) and
     * `userSectionName` answers from the fixture's map.
     */
    private class ScreenRecorder(private val stack: NavStack) : SessionsScreenActions {
        val calls = mutableListOf<String>()
        var grouped = false

        override val groupByProject: Boolean get() = grouped

        override fun setGroupByProject(on: Boolean) {
            grouped = on
            calls += "setGroupByProject($on)"
        }

        override fun newSection() {
            calls += "newSection()"
        }

        override fun collapseAll() {
            calls += "collapseAll()"
        }

        override fun toggleSection(id: String) {
            calls += "toggleSection($id)"
        }

        override fun openPinned() {
            calls += "openPinned()"
            stack.push(FolderRoute.pinned(), animated = false)
        }

        override fun openArchived() {
            calls += "openArchived()"
            stack.push(FolderRoute.archived(), animated = false)
        }

        override fun openSection(id: String, name: String) {
            calls += "openSection($id, $name)"
            stack.push(FolderRoute.section(id, name), animated = false)
        }

        override fun renameSection(id: String, currentName: String) {
            calls += "renameSection($id, $currentName)"
        }

        override fun deleteSection(id: String) {
            calls += "deleteSection($id)"
        }

        override fun userSectionName(id: String): String? = SessionsScreenFixtures.sectionNames[id]
    }

    private val feedback = RecordingFeedback()
    private val stack = NavStack(TabRootRoute(TabId.Sessions))
    private val recorder = ScreenRecorder(stack)
    private val contexts = CopyOnWriteArrayList<SessionRowContext>()

    /** The probe hero: captures the scrollFade lambda, counts compositions. */
    private var fadeFn: (() -> Float)? = null
    private var heroCalls = 0

    private val probeHero: (@Composable (Modifier, () -> Float) -> Unit) = { m, f ->
        heroCalls++
        fadeFn = f
        Box(m.testTag("wallpaper"))
    }

    /** 21's fixture artwork at opacity 1 — the bounds / fixed-frame hero. */
    private val fixtureHero: (@Composable (Modifier, () -> Float) -> Unit) = { m, f ->
        WallpaperArtwork(
            render = WallpaperRender(wallpaperFixture(), 1f),
            appear = { 1f },
            modifier = m,
            scrollFade = f,
        )
    }

    @Before
    fun reset() {
        feedback.haptics.clear()
        feedback.cues.clear()
    }

    // -------------------------------------------------------------- mount

    /**
     * Composes the screen once (a compose rule allows one `setContent` per
     * test). [hero] null → the production [sh.roboco.android.design.WallpaperHero]
     * (no wallpaper is set under Robolectric, so no node is emitted).
     */
    private var uiScope: CoroutineScope? = null

    private fun mount(
        sections: List<ListSection> = SessionsScreenFixtures.sections(),
        actions: SessionsScreenActions = recorder,
        hero: (@Composable (Modifier, () -> Float) -> Unit)? = null,
        showsNavOptions: Boolean = true,
        currentChatId: String? = null,
        loading: Boolean = false,
        listState: LazyListState = LazyListState(),
        rowWrapper: SessionRowWrapper = { ctx, row -> contexts += ctx; row(Modifier) },
        scrollToTop: TabScrollToTop? = null,
    ) {
        val resolvedHero: @Composable (Modifier, () -> Float) -> Unit =
            hero ?: { m, f -> WallpaperHero(modifier = m, scrollFade = f) }
        compose.setContent {
            // A composition scope: it carries the frame clock that
            // animateScrollToItem needs (a bare CoroutineScope has none).
            uiScope = rememberCoroutineScope()
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    CompositionLocalProvider(LocalFeedback provides feedback) {
                        ContextMenuHost {
                            GlassMenuHost(hazeState = rememberHazeState()) {
                                val content: @Composable () -> Unit = {
                                    NavStackHost(stack = stack, active = true) { entry ->
                                        val route = entry.route
                                        if (route is TabRootRoute && route.tab == TabId.Sessions) {
                                            SessionsScreenContent(
                                                sections = sections,
                                                collapsedIds = emptySet(),
                                                loading = loading,
                                                actions = actions,
                                                showsNavOptions = showsNavOptions,
                                                currentChatId = currentChatId,
                                                listState = listState,
                                                bottomPadding = 0.dp,
                                                hero = resolvedHero,
                                                rowWrapper = rowWrapper,
                                            )
                                        } else {
                                            // 45's folder pages (the pushed
                                            // routes render like on device).
                                            ShellRouteContent(route)
                                        }
                                    }
                                }
                                val registry = scrollToTop
                                if (registry != null) {
                                    CompositionLocalProvider(
                                        LocalTabId provides TabId.Sessions,
                                        LocalTabScrollToTop provides registry,
                                    ) { content() }
                                } else {
                                    content()
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ helpers

    /** px of [v] dp at the test density (3). */
    private fun dp(v: Float): Float = with(compose.density) { v.dp.toPx() }

    /** `getBoundsInRoot` is a [androidx.compose.ui.unit.DpRect]; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect =
        with(compose.density) { getBoundsInRoot().toRect() }

    /** The probe hero's captured scrollFade value. */
    private fun fade(): Float = requireNotNull(fadeFn) { "the probe hero never composed" }()

    /** Scrolls [distanceDp] forward (negative = backward) and settles. */
    private fun scrollBy(listState: LazyListState, distanceDp: Float) {
        compose.runOnUiThread { runBlocking { listState.scrollBy(dp(distanceDp)) } }
        compose.waitForIdle()
    }

    /** Long-presses [tag] with the clock auto-advancing (the gesture needs time). */
    private fun longPress(tag: String) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(tag).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** Opens the ⋯ menu and settles the open spring. */
    private fun openOptionsMenu() {
        compose.onNodeWithTag("sessions-options").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /** Picks [tag] from an open menu and runs the close fade + next-frame action out. */
    private fun pick(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    /**
     * Dismisses an open context menu (the scrim tap). Not `performClick()`:
     * that taps the backdrop's centre (437 dp), and a header's menu opens
     * below the header — for P0 the "Delete" row sits there, so the "dismiss"
     * picked Delete (CI r33). The tap goes to bare backdrop near the
     * bottom-start corner instead (ContextMenuTest's convention).
     */
    private fun dismissMenu() {
        compose.onNodeWithTag("context-menu-backdrop").performTouchInput {
            click(Offset(dp(24f), height - dp(24f)))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /**
     * Asserts the one *displayed* large title reads [title]. After a push
     * the root page stays composed under the pushed one (UIKit keeps the
     * below view controller); NavStackHost only stops placing it, and an
     * unplaced node keeps its semantics in the test tree on this Compose
     * version, so `onNodeWithTag("nav-large-title")` sees both titles (CI
     * r33). `isDisplayed()` rejects a node with an unplaced ancestor.
     */
    private fun assertDisplayedLargeTitle(title: String) {
        val titles = compose.onAllNodesWithTag("nav-large-title")
        val count = titles.fetchSemanticsNodes().size
        val shown = (0 until count).map { titles[it] }.filter { it.isDisplayed() }
        assertEquals("exactly one large title is displayed", 1, shown.size)
        shown.single().assert(hasText(title))
    }

    private val hasShowMenuAction = SemanticsMatcher("'Show menu' long-click action") {
        it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Show menu"
    }

    // ---------------------------------------------------------- geometry

    @Test
    fun theRootShowsTheLargeTitleTheSectionsAndTheMoreCircle() {
        mount()
        compose.onNodeWithTag("tab-root-sessions").assertExists()
        compose.onNodeWithTag("nav-large-title").assert(hasText("Sessions"))
        compose.onNodeWithTag("nav-back").assertDoesNotExist() // the root: no back circle
        compose.onNodeWithTag("section-pinned").assertExists()
        compose.onNodeWithTag("section-sec-p0").assertExists()
        compose.onNodeWithTag("section-sec-mobile").assertExists()
        compose.onNodeWithTag("section-recent").assertExists()
        compose.onNodeWithTag("session-chat-veil").assertExists()
        compose.onNodeWithTag("session-chat-tool-colors").assertExists()
        compose.onNodeWithTag("session-chat-i18n").assertExists()

        // 44 × 44, 16 dp from the end edge, centred in the nav row under a
        // 0 dp status bar; "More" is its a11y label.
        val button = compose.onNodeWithTag("sessions-options").pxBounds()
        assertEquals(dp(44f), button.width, 1f)
        assertEquals(dp(44f), button.height, 1f)
        assertEquals(dp(402f - 16f), button.right, 1f)
        assertEquals(dp(22f), button.center.y, 1f)
        compose.onNodeWithTag("sessions-options").assert(hasContentDescription("More"))
    }

    // -------------------------------------------------------- the ⋯ menu

    @Test
    fun tappingTheMoreCircleOpensTheFourRowMenu() {
        mount()
        openOptionsMenu()
        compose.onNodeWithTag("glass-menu").assertExists()
        compose.onNodeWithText("New Section\u2026").assertExists()
        compose.onNodeWithText("Collapse All").assertExists()
        compose.onNodeWithText("Group by Project").assertExists()
        compose.onNodeWithText("Archived").assertExists()
        compose.onNodeWithTag("sessions-options-new-section").assertExists()
        compose.onNodeWithTag("sessions-options-collapse-all").assertExists()
        compose.onNodeWithTag("sessions-options-group-by-project").assertExists()
        compose.onNodeWithTag("sessions-options-archived").assertExists()
        // One flat list: no title, no separators, no drill-in.
        compose.onNodeWithTag("glass-menu-back").assertDoesNotExist()
        compose.onNodeWithTag("glass-menu-loading").assertDoesNotExist()
        // An outside tap closes it.
        compose.onNodeWithTag("glass-menu-scrim").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertTrue("opening alone calls nothing", recorder.calls.isEmpty())
    }

    @Test
    fun pickingGroupByProjectFlipsTheLiveValueReadAtOpenTime() {
        mount()
        recorder.grouped = false
        openOptionsMenu()
        pick("sessions-options-group-by-project")
        assertEquals(listOf("setGroupByProject(true)"), recorder.calls)

        // Flipped "in Settings" while the screen stays: the next pick reads
        // the new value, not the one the menu showed when it opened.
        openOptionsMenu()
        pick("sessions-options-group-by-project")
        assertEquals(listOf("setGroupByProject(true)", "setGroupByProject(false)"), recorder.calls)
    }

    @Test
    fun newSectionAndCollapseAllRowsCallThrough() {
        mount()
        openOptionsMenu()
        pick("sessions-options-new-section")
        // A pick closes the menu (15's row tap: close fade, then the
        // action): the second row needs the menu opened again.
        openOptionsMenu()
        pick("sessions-options-collapse-all")
        assertEquals(listOf("newSection()", "collapseAll()"), recorder.calls)
    }

    @Test
    fun archivedPushesTheArchivedFolderPageOnThisStack() {
        mount()
        openOptionsMenu()
        pick("sessions-options-archived")
        assertEquals(listOf("openArchived()"), recorder.calls)
        val top = stack.top.route
        assertTrue("the top route is the Archived folder page, was $top", top is FolderRoute && top.folderId == "archived")
        assertDisplayedLargeTitle("Archived")
    }

    // -------------------------------------------------- header long-press

    /**
     * The port of `iosui:SessionFlowTests.swift:24-40`: long-press the
     * Pinned header, tap "Open" — the Pinned folder page appears (not in
     * Edit mode; 47 has not landed, so the page has no trailing item).
     */
    @Test
    fun thePinnedHeaderLongPressOpensThePinnedPage() {
        mount()
        longPress("section-pinned")
        compose.onNodeWithTag("context-menu-overlay").assertExists()
        compose.onNodeWithText("Open").assertExists()
        compose.onNodeWithText("Reorder\u2026").assertExists()
        compose.onNodeWithTag("section-menu-open").assertExists()
        compose.onNodeWithTag("section-menu-reorder").assertExists()
        assertEquals(listOf(Haptic.LongPress), feedback.haptics)

        compose.mainClock.advanceTimeBy(600) // past the lift + the menu open
        compose.waitForIdle()
        pick("section-menu-open")

        assertEquals(listOf("openPinned()"), recorder.calls)
        val top = stack.top.route
        assertTrue("the top route is the Pinned folder page, was $top", top is FolderRoute && top.folderId == "pinned")
        assertDisplayedLargeTitle("Pinned")
    }

    @Test
    fun thePinnedReorderRowOpensTheSamePage() {
        mount()
        longPress("section-pinned")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        pick("section-menu-reorder")
        assertEquals(listOf("openPinned()"), recorder.calls)
        val top = stack.top.route
        assertTrue(top is FolderRoute && top.folderId == "pinned")
    }

    @Test
    fun aUserSectionMenuRenamesWithTheRawNameAndDeletesWithNoDialog() {
        mount()
        longPress("section-sec-p0")
        compose.onNodeWithTag("context-menu-overlay").assertExists()
        compose.onNodeWithTag("section-menu-open").assertExists()
        compose.onNodeWithTag("section-menu-rename").assertExists()
        compose.onNodeWithTag("section-menu-delete").assertExists()

        // Delete: no confirmation — no alert, no action sheet, straight to
        // 42's write (iOS `:396`).
        pick("section-menu-delete")
        compose.mainClock.advanceTimeBy(600) // the overlay teardown
        compose.waitForIdle()
        assertEquals(listOf("deleteSection(sec-p0)"), recorder.calls)
        compose.onNodeWithTag("alert").assertDoesNotExist()
        compose.onNodeWithTag("action-sheet").assertDoesNotExist()
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()

        // Rename: the raw name ("P0"), never 40's suffixed header title.
        longPress("section-sec-p0")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        pick("section-menu-rename")
        assertEquals(
            listOf("deleteSection(sec-p0)", "renameSection(sec-p0, P0)"),
            recorder.calls,
        )
    }

    @Test
    fun recentHasNoMenuAndNoHaptic() {
        mount()
        longPress("section-recent")
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertTrue("no haptic without a menu", feedback.haptics.isEmpty())
        assertTrue(recorder.calls.isEmpty())

        // A tap on it still folds (the iOS rule: no menu, but a normal
        // header).
        compose.onNodeWithTag("section-recent").performClick()
        compose.waitForIdle()
        assertEquals(listOf("toggleSection(recent)"), recorder.calls)
    }

    @Test
    fun projectGroupsHaveNoMenuAndNoHaptic() {
        mount(sections = SessionsScreenFixtures.groupedSections())
        longPress("section-project:zeron")
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
        assertTrue("project groups never lift", feedback.haptics.isEmpty())
        longPress("section-home:device-A")
        compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
        assertTrue("home groups never lift", feedback.haptics.isEmpty())
        assertTrue(recorder.calls.isEmpty())
    }

    // ------------------------------------------------------------ folding

    @Test
    fun aHeaderTapTogglesOnceAndALongPressDoesNot() {
        mount()
        compose.onNodeWithTag("section-sec-p0").performClick()
        compose.waitForIdle()
        assertEquals(listOf("toggleSection(sec-p0)"), recorder.calls)

        longPress("section-sec-p0")
        assertEquals("the long press opens the menu, not the fold", listOf(Haptic.LongPress), feedback.haptics)
        dismissMenu()
        assertEquals("the swallowed release never folds", listOf("toggleSection(sec-p0)"), recorder.calls)
    }

    @Test
    fun theHeaderIsOneSemanticsNodeWithTheFoldClickAndTheShowMenuAction() {
        mount()
        assertEquals(
            "exactly one node carries the section-pinned tag",
            1,
            compose.onAllNodesWithTag("section-pinned").fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("section-pinned")
            .assert(hasClickAction())
            .assert(hasShowMenuAction)
        assertEquals(
            1,
            compose.onAllNodesWithTag("section-recent").fetchSemanticsNodes().size,
        )
    }

    // -------------------------------------------------------------- hero

    @Test
    fun theHeroFrameSitsAtTheTopOfTheScreenAtTheTicketGeometry() {
        mount(hero = fixtureHero)
        val hero = compose.onNodeWithTag("wallpaper")
        hero.assertExists()
        hero.assertTopPositionInRootIsEqualTo(0.dp)
        val bounds = hero.getBoundsInRoot()
        assertEquals(402f, bounds.width.value, 0.5f)
        assertEquals(629.28f, bounds.height.value, 0.5f)
        assertEquals(0f, bounds.left.value, 0.5f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h1100dp-xxhdpi")
    fun theHeroHeightCapsAt760OnATallWindow() {
        mount(hero = fixtureHero)
        val bounds = compose.onNodeWithTag("wallpaper").getBoundsInRoot()
        assertEquals(760f, bounds.height.value, 0.5f)
        assertEquals(0f, bounds.top.value, 0.5f)
    }

    @Test
    fun noWallpaperSetEmitsNoHeroNode() {
        mount() // the production WallpaperHero: no store entry under Robolectric
        compose.onNodeWithTag("wallpaper").assertDoesNotExist()
        compose.onNodeWithTag("tab-root-sessions").assertExists()
    }

    @Test
    fun theFadeFollowsTheScrollTable() {
        val listState = LazyListState()
        mount(sections = SessionsScreenFixtures.longSections(), hero = probeHero, listState = listState)

        assertEquals("rest", 1f, fade(), 1e-3f)
        scrollBy(listState, 52f)
        assertEquals("52 dp", 0.862277f, fade(), 0.005f)
        scrollBy(listState, 188.784f - 52f)
        assertEquals("188.78 dp", 0.5f, fade(), 0.005f)
        scrollBy(listState, 377.568f - 188.784f)
        assertEquals("377.57 dp", 0f, fade(), 0.005f)
        scrollBy(listState, 100f)
        assertTrue("past the distance the raw fade is negative", fade() < 0f)

        // Back to the top (the scroll clamps at rest): the fade returns
        // to 1, and a further pull-down keeps it there (iOS keeps it at 1
        // through the rubber band; Compose's programmatic scroll clamps
        // instead — the same fade either way).
        scrollBy(listState, -1000f)
        assertEquals(1f, fade(), 1e-3f)
        scrollBy(listState, -100f)
        assertEquals(1f, fade(), 1e-3f)
    }

    @Test
    fun theHeroStaysFixedWhileTheListScrolls() {
        val listState = LazyListState()
        mount(sections = SessionsScreenFixtures.longSections(), hero = fixtureHero, listState = listState)
        scrollBy(listState, 200f)
        compose.onNodeWithTag("wallpaper").assertTopPositionInRootIsEqualTo(0.dp)
        scrollBy(listState, 200f)
        compose.onNodeWithTag("wallpaper").assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun scrollingDoesNotRecomposeTheScreen() {
        val listState = LazyListState()
        mount(sections = SessionsScreenFixtures.longSections(), hero = probeHero, listState = listState)
        val composedOnce = heroCalls
        assertTrue("the hero composed", composedOnce > 0)
        scrollBy(listState, 30f)
        scrollBy(listState, 60f)
        scrollBy(listState, 90f)
        assertEquals("scrolling recomposed the backdrop", composedOnce, heroCalls)
    }

    @Test
    fun reTappingTheTabScrollsToTopAndTheFadeReturnsToOne() {
        val registry = TabScrollToTop()
        val listState = LazyListState()
        mount(
            sections = SessionsScreenFixtures.longSections(),
            hero = probeHero,
            listState = listState,
            scrollToTop = registry,
        )
        // 26's registration: the tab's root owns the handler.
        val handler = registry.handlers[TabId.Sessions]
        assertNotNull("RegisterScrollToTop registered the root's list", handler)

        // Sample the fade at rest first. On device the hero reads the fade
        // in its layer every frame, so ScrollTravel tracks the scroll by a
        // common key; the probe hero never reads it, so without this sample
        // the first read after the 188.78 dp jump has no previous layout
        // and falls back to the mean-size estimate (CI r33: 0.436, not
        // 0.5). theFadeFollowsTheScrollTable samples at rest the same way.
        assertEquals("rest", 1f, fade(), 1e-3f)
        scrollBy(listState, 188.784f)
        assertEquals(0.5f, fade(), 0.005f)

        // The tab bar's re-tap runs the handler (animated to item 0).
        compose.runOnUiThread { uiScope!!.launch { handler!!.scrollToTop() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals("the hero returned to full alpha", 1f, fade(), 0.005f)
        assertEquals(0, listState.firstVisibleItemIndex)
    }

    // -------------------------------------------------- states / switches

    @Test
    fun theEmptyAndLoadingOverlaysShowInsideTheSameScaffold() {
        mount(sections = emptyList(), hero = fixtureHero)
        compose.onNodeWithTag("sessions-empty").assertExists()
        compose.onNodeWithTag("sessions-loading").assertDoesNotExist()
        // The hero still shows under the copy.
        compose.onNodeWithTag("wallpaper").assertExists()
    }

    @Test
    fun aMissingFrontPageShowsTheLoadingSpinner() {
        mount(sections = emptyList(), loading = true, hero = fixtureHero)
        compose.onNodeWithTag("sessions-loading").assertExists()
        compose.onNodeWithTag("sessions-empty").assertDoesNotExist()
    }

    @Test
    fun noNavOptionsRendersNoTrailingItemAndCurrentChatIdReachesTheRows() {
        mount(showsNavOptions = false, currentChatId = "chat-scroll-pinning")
        compose.onNodeWithTag("sessions-options").assertDoesNotExist()
        compose.onNodeWithTag("nav-large-title").assert(hasText("Sessions"))

        val byId = contexts.associateBy { it.vm.id }
        assertTrue("the current row is flagged", byId.getValue("chat-scroll-pinning").isCurrent)
        assertFalse(byId.getValue("chat-veil").isCurrent)
        assertFalse(byId.getValue("chat-i18n").isCurrent)
    }
}
