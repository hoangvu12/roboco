package sh.roboco.android.shell.tablet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.sessions.LocalSidebarFixtures
import sh.roboco.android.sessions.SearchFixtures
import sh.roboco.android.sessions.SessionSearch
import sh.roboco.android.sessions.SessionsScreenFixtures
import sh.roboco.android.sessions.SidebarFixtures
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DraftHandoff
import uniffi.roboco_core.SearchField
import uniffi.roboco_core.SearchHit
import uniffi.roboco_core.SessionRow

/** "Wrangler deploy hygiene" — the one hit of the "wrangler" query. */
private val deployRow: SessionRow =
    SessionsScreenFixtures.recentRows.first { it.id == "chat-deploy" }

/**
 * The tablet sidebar (android-ui-parity #105) at 360 × 800 dp × 2.0
 * (xhdpi): the whole [Sidebar] over the ticket's fixture (46's `1-off`
 * sections, a long-Pinned workspace, a fake [SessionSearch] answering
 * "tool" and "wrangler"), with `topInset = 24` (Robolectric reports no
 * status bar, so `navBottom = 68`, the band at rest at 120, and the
 * list's bottom padding 56 — no navigation bar either).
 *
 * The host mirrors 46's mount order (`ContextMenuHost` outermost, then
 * `GlassMenuHost` for 45's pushed folder pages) over a real [SidebarState]
 * and a recording [AppRouter]; the composition runs under the frozen glyph
 * clock (the ticket's harness rule) while the main clock advances for
 * gestures. One `setContent` per test; the host's `inTree` / `shown` /
 * `currentChat` flags drive the removal, hide and current-row cases.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class SidebarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ------------------------------------------------------------ the fakes

    /**
     * The fake core search: logs every trimmed query, waits on [gate]
     * when set (the stale-hits case), answers 49's "tool" hits, the
     * "wrangler" deploy hit and nothing else.
     */
    private class FakeSearch {
        val calls = CopyOnWriteArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null

        val asSearch: SessionSearch = { q, _ ->
            calls += q
            gate?.await()
            when (q) {
                "tool" -> SearchFixtures.hits.map { SearchHit(it, 100u, SearchField.TITLE) }
                "wrangler" -> listOf(SearchHit(deployRow, 100u, SearchField.TITLE))
                else -> emptyList()
            }
        }
    }

    /** The recording router: one line per call, the row taps assert on it. */
    private class RecordingRouter : AppRouter {
        val calls = mutableListOf<String>()
        override fun openSession(chatId: String) {
            calls += "openSession($chatId)"
        }
        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            calls += "openSession($chatId, handoff)"
        }
        override fun presentNewSession(prompt: String?) {}
        override fun showSettings() {}
        override fun showSearch() {}
        override fun showSessionOpenError() {}
        override fun popToFrontPage() {}
    }

    private val state = SidebarState()
    private val router = RecordingRouter()
    private val fake = FakeSearch()
    private val fixtures: SidebarFixtures = sidebarGalleryFixtures(fake.asSearch)

    // Host flags: one setContent per test, flipped through writeOnUi().
    private var currentChat by mutableStateOf<String?>(null)
    private var shown by mutableStateOf(true)
    private var inTree by mutableStateOf(true)

    // -------------------------------------------------------------- mount

    private fun mount() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    ContextMenuHost {
                        GlassMenuHost(hazeState = rememberHazeState()) {
                            CompositionLocalProvider(LocalSidebarFixtures provides fixtures) {
                                if (inTree) {
                                    Sidebar(
                                        state = state,
                                        currentChatId = currentChat,
                                        shown = shown,
                                        router = router,
                                        topInset = 24.dp,
                                    )
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

    /** px of [v] dp at the test density (2). */
    private fun dp(v: Float): Float = with(compose.density) { v.dp.toPx() }

    /** `getBoundsInRoot` is a DpRect; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect =
        with(compose.density) { getBoundsInRoot().toRect() }

    /** Runs a test-thread snapshot write and publishes it to the Recomposer. */
    private fun writeOnUi(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    /** Focuses the field, types [text] and waits for the search to land. */
    private fun type(text: String) {
        compose.onNodeWithTag("sidebar-search").performClick()
        compose.onNodeWithTag("sidebar-search").assertIsFocused()
        compose.onNodeWithTag("sidebar-search").performTextInput(text)
        settle()
    }

    /** Lets the (real-Dispatchers.Default) fake search land on main. */
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    /** Polls until [tag] composes (the async search), with a deadline. */
    private fun awaitTag(tag: String, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
        }
    }

    /** Polls until [tag] leaves the tree (the async swap), with a deadline. */
    private fun awaitGone(tag: String, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
        }
    }

    /** Scrolls the root's list [distanceDp] forward (negative = backward). */
    private fun scrollBy(distanceDp: Float) {
        compose.runOnUiThread { runBlocking { state.listState.scrollBy(dp(distanceDp)) } }
        compose.waitForIdle()
    }

    /** Long-presses [tag] with the clock auto-advancing (the gesture needs time). */
    private fun longPress(tag: String) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(tag).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** Picks [tag] from an open menu and runs the close fade out. */
    private fun pick(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    /** Fling-scrolls the on-screen list to its end (the pushed folder's). */
    private fun flingToBottom(times: Int = 5) {
        repeat(times) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
    }

    /** The row's image at 2 px/dp. */
    private fun rowPixels(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    private fun assertPixel(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, what: String) {
        val c = px[x, y]
        fun ok(v: Float, e: Int) = abs(v - e / 255f) <= 2f / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).roundToInt()}, ${(c.green * 255f).roundToInt()}, " +
                "${(c.blue * 255f).roundToInt()}), expected ($r, $g, $b) ±2",
            ok(c.red, r) && ok(c.green, g) && ok(c.blue, b),
        )
    }

    /**
     * The large title on screen: a page pushed over stays composed but
     * unplaced, so `nav-large-title` can match twice — exactly one must
     * be displayed, and it carries [title].
     */
    private fun assertDisplayedLargeTitle(title: String) {
        val titles = compose.onAllNodesWithTag("nav-large-title")
        val count = titles.fetchSemanticsNodes().size
        val shown = (0 until count).map { titles[it] }.filter { it.isDisplayed() }
        assertEquals("exactly one large title is displayed", 1, shown.size)
        shown.single().assert(hasText(title))
    }

    /** Every node tagged [tag] that is actually displayed (pushed-under pages stay composed, unplaced). */
    private fun displayedNodes(tag: String): List<SemanticsNodeInteraction> {
        val all = compose.onAllNodesWithTag(tag)
        val count = all.fetchSemanticsNodes().size
        return (0 until count).map { all[it] }.filter { it.isDisplayed() }
    }

    /** Pushes the Pinned folder non-animated (the menu path has its own test). */
    private fun pushPinned() {
        writeOnUi { state.stack.push(FolderRoute.pinned(), animated = false) }
        compose.waitForIdle()
        settle()
    }

    // ---------------------------------------------------------- geometry

    @Test
    fun theSidebarShowsTheTitleTheFieldAndNoOptionsCircle() {
        mount()
        compose.onNodeWithTag("sidebar").assertExists()
        assertDisplayedLargeTitle("Sessions")
        compose.onNodeWithTag("sessions-options").assertDoesNotExist()

        // The band at rest: top = 68 + 52 = 120, full width, 52 high.
        val band = compose.onNodeWithTag("sidebar-search-band").pxBounds()
        assertEquals(dp(120f), band.top, 1f)
        assertEquals(dp(0f), band.left, 1f)
        assertEquals(dp(360f), band.width, 1f)
        assertEquals(dp(52f), band.height, 1f)

        // The text field: starts 38 dp inside the capsule (16 + 38 = 54),
        // ends 12 dp before its end (344 − 12 = 332), vertically centred
        // in the 40 dp capsule (centre y = 126 + 20 = 146) — the capsule
        // itself is 16 … 344 × 126 … 166.
        val field = compose.onNodeWithTag("sidebar-search").pxBounds()
        assertEquals(dp(54f), field.left, 1f)
        assertEquals(dp(332f), field.right, 1f)
        assertEquals(dp(146f), field.center.y, 1.5f)
        assertTrue(field.top >= dp(126f) && field.bottom <= dp(166f))

        // The placeholder shows while empty, no clear button yet, and the
        // field's a11y label is its placeholder (UIKit's rule).
        compose.onNodeWithText("Search").assertExists()
        compose.onNodeWithTag("sidebar-search").assert(hasContentDescription("Search"))
        compose.onNodeWithTag("sidebar-search-clear").assertDoesNotExist()

        // 25's spacer item: the first section header starts at 68 + 52 + 52.
        val pinned = compose.onNodeWithTag("section-pinned").pxBounds()
        assertEquals(dp(172f), pinned.top, 1.5f)

        // The front page is 46's, unfiltered: every section is there.
        compose.onNodeWithTag("section-sec-p0").assertExists()
        compose.onNodeWithTag("section-sec-mobile").assertExists()
        compose.onNodeWithTag("section-recent").assertExists()
        compose.onNodeWithTag("session-chat-veil").assertExists()
    }

    @Test
    fun theFieldRidesUpWithTheLargeTitleAndPinsUnderTheNavRow() {
        mount()
        scrollBy(100f) // c = 52: the large title has fully collapsed

        // The band pins at navBottom (68); the capsule sits at 74 … 114.
        val band = compose.onNodeWithTag("sidebar-search-band").pxBounds()
        assertEquals(dp(68f), band.top, 1f)
        assertEquals(dp(52f), band.height, 1f)

        val field = compose.onNodeWithTag("sidebar-search").pxBounds()
        assertEquals(dp(54f), field.left, 1f)
        assertEquals(dp(94f), field.center.y, 1.5f) // 74 + 20
        assertTrue(field.top >= dp(74f) && field.bottom <= dp(114f))

        // Scrolling further keeps it pinned: it never hides.
        scrollBy(60f)
        assertEquals(dp(68f), compose.onNodeWithTag("sidebar-search-band").pxBounds().top, 1f)
    }

    @Test
    fun theCapsuleGeometryWithTextAndTheClearButton() {
        mount()
        type("a")

        // The clear button's 44 × 40 touch target: top 126, its right edge
        // 0.2 dp inside the capsule's end (344 − 0.2 = 343.8), so the glyph
        // box ends exactly 12 dp before it.
        val clear = compose.onNodeWithTag("sidebar-search-clear").pxBounds()
        assertEquals(dp(126f), clear.top, 1f)
        assertEquals(dp(40f), clear.height, 1f)
        assertEquals(dp(343.8f), clear.right, 1f)
        compose.onNodeWithTag("sidebar-search-clear").assert(hasContentDescription("Clear text"))
    }

    // ------------------------------------------------- the band's hit zone

    @Test
    fun theBandBlocksTouchesToTheRowsUnderIt() {
        mount()
        // At rest, a tap in the band outside the capsule (x 4 < 16 — the
        // ticket's (4, 140) window point) opens nothing and focuses
        // nothing.
        compose.onNodeWithTag("sidebar-search-band").performTouchInput {
            click(Offset(dp(4f), dp(20f)))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sidebar-search").assertIsNotFocused()
        assertTrue(router.calls.isEmpty())
        compose.onNodeWithTag("session-chat-veil").assertExists() // Pinned not folded

        // Scrolled: rows run under the pinned band — the same tap still
        // opens nothing (25's nav-row hit rule).
        scrollBy(100f)
        compose.onNodeWithTag("sidebar-search-band").performTouchInput {
            click(Offset(dp(4f), dp(20f)))
        }
        compose.waitForIdle()
        assertTrue(router.calls.isEmpty())
        compose.onNodeWithTag("session-chat-veil").assertExists()
    }

    // -------------------------------------------------------- the query

    @Test
    fun typingWranglerReplacesTheSectionsWithTheHeaderLessHits() {
        mount()
        type("wrangler")
        awaitGone("section-pinned")

        // Exactly the hit, header-less: no section headers remain.
        compose.onNodeWithTag("session-chat-deploy").assertExists()
        compose.onNodeWithTag("session-chat-veil").assertDoesNotExist()
        compose.onNodeWithTag("section-pinned").assertDoesNotExist()
        compose.onNodeWithTag("section-sec-p0").assertDoesNotExist()
        compose.onNodeWithTag("section-recent").assertDoesNotExist()
        // The query is the raw text.
        assertEquals("wrangler", state.query)
        assertEquals(listOf("wrangler"), fake.calls)
    }

    @Test
    fun aWhitespaceOnlyQueryKeepsTheFullFrontPage() {
        mount()
        type("   ")

        compose.onNodeWithTag("section-pinned").assertExists()
        compose.onNodeWithTag("section-sec-p0").assertExists()
        compose.onNodeWithTag("section-sec-mobile").assertExists()
        compose.onNodeWithTag("section-recent").assertExists()
        // Not "No Results", and the clear button shows.
        compose.onNodeWithTag("search-no-results").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-search-clear").assertExists()
        // Whitespace never reaches the core.
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun theClearButtonRestoresTheFrontPageAndKeepsTheFocus() {
        mount()
        type("tool")
        awaitTag("session-chat-tool-rail")

        compose.onNodeWithTag("sidebar-search-clear").performClick()
        settle()

        compose.onNodeWithTag("section-pinned").assertExists()
        compose.onNodeWithTag("session-chat-tool-rail").assertDoesNotExist()
        assertEquals("", state.query)
        // The field stays focused (UIKit begins editing on clear).
        compose.onNodeWithTag("sidebar-search").assertIsFocused()
    }

    @Test
    fun aQueryWithZeroHitsShowsTheNoResultsView() {
        mount()
        type("zzz")
        awaitTag("search-no-results")

        compose.onNodeWithTag("search-no-results").assertExists()
        compose.onNodeWithTag("section-pinned").assertDoesNotExist()
        compose.onNodeWithTag("session-chat-veil").assertDoesNotExist()
    }

    @Test
    fun staleHitsStayOnScreenWhileTheNewerSearchRuns() {
        mount()
        type("wrangler")
        awaitGone("section-pinned")

        // Block the next search; "wranglerx" is in flight.
        fake.gate = CompletableDeferred()
        compose.onNodeWithTag("sidebar-search").performTextInput("x")
        settle()
        // The search call itself runs on the real Dispatchers.Default
        // (SearchModel's `work`): settle() only advances virtual time, so on
        // a slow runner (windows-2022) "wranglerx" had not reached the fake
        // yet. Poll in real time, like awaitTag.
        val deadline = System.currentTimeMillis() + 3_000
        while (fake.calls.size < 2 && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
        }
        assertEquals(listOf("wrangler", "wranglerx"), fake.calls)

        // The previous hits stay on screen — no blank frame, no front
        // page flash.
        compose.onNodeWithTag("session-chat-deploy").assertExists()
        compose.onNodeWithTag("section-pinned").assertDoesNotExist()
        compose.onNodeWithTag("search-no-results").assertDoesNotExist()

        // Release: zero hits for "wranglerx" → the no-results view.
        fake.gate?.complete(Unit)
        awaitTag("search-no-results")
        compose.onNodeWithTag("session-chat-deploy").assertDoesNotExist()
    }

    @Test
    fun anArchivedHitGetsTheArchivedRowActionsOnly() {
        mount()
        type("tool")
        awaitTag("session-chat-tool-output")

        longPress("session-chat-tool-output")
        compose.onNodeWithTag("context-menu-overlay").assertExists()
        compose.onNodeWithTag("row-menu-unarchive").assertExists()
        compose.onNodeWithTag("row-menu-rename").assertExists()
        compose.onNodeWithTag("row-menu-archive").assertDoesNotExist()
        compose.onNodeWithTag("row-menu-pin").assertDoesNotExist()
        // Dismiss the menu (the scrim tap) on bare backdrop near the
        // bottom-start corner — a centre tap can land on a menu row.
        compose.onNodeWithTag("context-menu-backdrop").performTouchInput {
            click(Offset(24.dp.toPx(), height - 24.dp.toPx()))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    // ------------------------------------------------------- the IME

    @Test
    fun theImeSearchActionHidesTheImeAndClearsTheFocusKeepingTheResults() {
        mount()
        type("tool")
        awaitTag("session-chat-tool-rail")

        compose.onNodeWithTag("sidebar-search").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithTag("sidebar-search").assertIsNotFocused()
        assertEquals("tool", state.query)
        compose.onNodeWithTag("session-chat-tool-rail").assertExists()
    }

    // ------------------------------------------------------- row taps

    @Test
    fun rowTapsOpenTheSessionBesideTheSidebarFromTheRootAndTheResults() {
        mount()
        // A front-page row tap: one open, no stack change, nothing focused.
        compose.onNodeWithTag("session-chat-veil").performClick()
        compose.waitForIdle()
        assertEquals(listOf("openSession(chat-veil)"), router.calls)
        assertEquals(1, state.stack.entries.size)
        compose.onNodeWithTag("sidebar-search").assertIsNotFocused()

        // A results row tap (the field is focused from typing): the query
        // is kept, the field loses focus, the depth stays 1.
        type("tool")
        awaitTag("session-chat-tool-rail")
        compose.onNodeWithTag("session-chat-tool-rail").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf("openSession(chat-veil)", "openSession(chat-tool-rail)"),
            router.calls,
        )
        assertEquals(1, state.stack.entries.size)
        assertEquals("tool", state.query)
        compose.onNodeWithTag("sidebar-search").assertIsNotFocused()
    }

    @Test
    fun aRowTapOnAPushedFolderOpensTheSessionBesideTheSidebar() {
        mount()
        pushPinned()
        assertDisplayedLargeTitle("Pinned")

        // The root stays composed (unplaced) under the folder and also
        // has a chat-veil row: tap the displayed one, the folder's.
        displayedNodes("session-chat-veil").single().performClick()
        compose.waitForIdle()

        assertEquals(listOf("openSession(chat-veil)"), router.calls)
        assertEquals(2, state.stack.entries.size) // the folder stays on top
    }

    // --------------------------------------------------- the current row

    @Test
    fun theCurrentRowIsFilledRowActiveAndMovesWithinOneFrame() {
        mount()
        writeOnUi { currentChat = "chat-models" }
        compose.waitForIdle()

        // rowActive (white @ 0.9) over the page ≈ #FEFEFE, sampled 12 dp
        // inside the row's leading edge (left of the 20 dp text inset);
        // the neighbouring row stays the page (#F3F3F5).
        val models = rowPixels("session-chat-models")
        val veil = rowPixels("session-chat-veil")
        assertPixel(models, 24, models.height / 2, 254, 254, 254, "current centre-left")
        assertPixel(veil, 24, veil.height / 2, 243, 243, 245, "not-current centre-left")

        // The fill moves with no item animation in flight: one frame after
        // the change (the clock frozen), the other row is filled and the
        // first is not — nothing moves, no settle needed.
        compose.mainClock.autoAdvance = false
        writeOnUi { currentChat = "chat-veil" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        val models2 = rowPixels("session-chat-models")
        val veil2 = rowPixels("session-chat-veil")
        assertPixel(veil2, 24, veil2.height / 2, 254, 254, 254, "current after the move")
        assertPixel(models2, 24, models2.height / 2, 243, 243, 245, "not-current after the move")
    }

    @Test
    fun theCurrentRowFillAlsoWorksInResultsMode() {
        mount()
        type("tool")
        awaitTag("session-chat-tool-rail")
        writeOnUi { currentChat = "chat-tool-colors" }
        compose.waitForIdle()

        val px = rowPixels("session-chat-tool-colors")
        assertPixel(px, 24, px.height / 2, 254, 254, 254, "current hit centre-left")
    }

    // --------------------------------------------------------- folders

    @Test
    fun aHeaderLongPressOpenPushesTheFolderInsideTheSidebar() {
        mount()
        // Scroll first: the back-pop must return to this position. 60, not
        // 80: past the large title (firstIndex > 0, c = 52) but short of
        // tucking the 40 dp header under the pinned search band (68 … 120)
        // — at 80 its centre (112) sat under the band, which took the press.
        scrollBy(60f)
        val firstIndex = state.listState.firstVisibleItemIndex
        assertTrue(firstIndex > 0)

        longPress("section-pinned")
        compose.onNodeWithTag("context-menu-overlay").assertExists()
        pick("section-menu-open")

        // The folder page pushed INSIDE the sidebar (24's push), and the
        // root (field included) slid away with the push.
        assertEquals(2, state.stack.entries.size)
        assertDisplayedLargeTitle("Pinned")
        assertEquals(1, displayedNodes("session-chat-veil").size) // the folder's rows
        assertTrue(displayedNodes("sidebar-search").isEmpty())

        // Back returns to the root with the query and the scroll intact.
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600) // the pop's spring
        compose.waitForIdle()
        assertEquals(1, state.stack.entries.size)
        assertDisplayedLargeTitle("Sessions")
        assertEquals(firstIndex, state.listState.firstVisibleItemIndex)
    }

    @Test
    fun pushingAFolderWhileTheFieldIsFocusedDropsTheFocus() {
        mount()
        type("tool")
        awaitTag("session-chat-tool-rail")
        compose.onNodeWithTag("sidebar-search").assertIsFocused()

        pushPinned()
        assertDisplayedLargeTitle("Pinned")
        // The root (and the field with it) left the screen: no caret.
        // The root may stay composed but unplaced, so assert "not
        // displayed and not focused" rather than absence.
        assertTrue(displayedNodes("sidebar-search").isEmpty())
        if (compose.onAllNodesWithTag("sidebar-search").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("sidebar-search").assertIsNotFocused()
        }
        assertEquals("tool", state.query) // the text survives
    }

    // ------------------------------------------ focusSearch / showSessions

    @Test
    fun focusSearchPopsToRootWithoutAnimationAndFocusesTheNextFrame() {
        mount()
        pushPinned()
        assertEquals(2, state.stack.entries.size)

        compose.mainClock.autoAdvance = false
        writeOnUi { state.focusSearch() }
        // The pop was not animated: within one frame the stack is at the
        // root with no transition left, and no 500 ms push/pop frames ran.
        // Exactly one frame: advanceTimeBy(32) runs two, and the second is
        // already the field's withFrameNanos that consumes the request.
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, state.stack.entries.size)
        assertNull(state.stack.transition)
        assertTrue(state.pendingFocus)

        // One frame later the field is focused, the caret at the end, the
        // request consumed.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("sidebar-search").assertIsFocused()
        assertFalse(state.pendingFocus)
        assertDisplayedLargeTitle("Sessions")
    }

    @Test
    fun showSessionsEndsTheSearchEmptiesTheTextAndPopsToRoot() {
        mount()
        type("tool")
        awaitTag("session-chat-tool-rail")
        pushPinned()

        compose.mainClock.autoAdvance = false
        writeOnUi { state.showSessions() }
        compose.mainClock.advanceTimeBy(32)

        assertEquals("", state.query)
        assertEquals(1, state.stack.entries.size)
        assertNull(state.stack.transition)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // The front page is back; the field is not focused.
        compose.onNodeWithTag("section-pinned").assertExists()
        compose.onNodeWithTag("session-chat-tool-rail").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-search").assertIsNotFocused()
        assertFalse(state.pendingDropFocus)
    }

    // ---------------------------------------------------- shell swaps

    @Test
    fun recomposingTheSidebarWithTheSameStateKeepsEverythingAndDoesNotRefocus() {
        mount()
        scrollBy(120f)
        val firstIndex = state.listState.firstVisibleItemIndex
        assertTrue(firstIndex > 0)

        // A consumed focus request…
        writeOnUi { state.focusSearch() }
        compose.mainClock.advanceTimeBy(32)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("sidebar-search").assertIsFocused()
        assertFalse(state.pendingFocus)

        // …the 840 dp round trip: the sidebar leaves and re-enters
        // composition with the same state.
        writeOnUi { inTree = false }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("sidebar").fetchSemanticsNodes().isEmpty())
        assertEquals(1, state.stack.entries.size)

        writeOnUi { inTree = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()

        // The stack, the text and the scroll position survive; the field is
        // NOT focused (the consumed flag never re-runs) and no IME is up.
        compose.onNodeWithTag("sidebar").assertExists()
        compose.onNodeWithTag("sidebar-search").assertIsNotFocused()
        assertEquals(firstIndex, state.listState.firstVisibleItemIndex)
    }

    // ---------------------------------------------------- bottom padding

    @Test
    fun theRootListsLastRowClearsTheToolbarArea() {
        mount()
        compose.runOnUiThread { runBlocking { state.listState.scrollBy(dp(10_000f)) } }
        compose.waitForIdle()

        // bottomPadding = navigationBars(0) + 12 + 44 = 56: the last row's
        // bottom sits 56 dp above the window bottom (744).
        val last = compose.onNodeWithTag("session-chat-launch-post").pxBounds()
        assertEquals(dp(744f), last.bottom, 1.5f)
    }

    @Test
    fun aPushedFolderGetsTheSameBottomPadding() {
        mount()
        pushPinned()
        flingToBottom()

        val last = compose.onNodeWithTag("session-chat-models-6").pxBounds()
        assertEquals(dp(744f), last.bottom, 1.5f)
    }

    // --------------------------------------------------------- back

    @Test
    fun systemBackDoesNotPopASidebarFolderWhileTheSidebarIsHidden() {
        mount()
        pushPinned()
        assertEquals(2, state.stack.entries.size)

        writeOnUi { shown = false }
        compose.waitForIdle()
        // The stack's back handler is off while hidden: no enabled
        // callbacks, and plain back pops nothing.
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(2, state.stack.entries.size) // not popped

        // Shown again, back pops it.
        writeOnUi { shown = true }
        compose.waitForIdle()
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(1, state.stack.entries.size)
    }
}
