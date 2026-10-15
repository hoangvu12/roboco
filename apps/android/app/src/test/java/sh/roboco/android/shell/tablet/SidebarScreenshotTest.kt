package sh.roboco.android.shell.tablet

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.rememberHazeState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.sessions.LocalSidebarFixtures
import sh.roboco.android.sessions.SearchFixtures
import sh.roboco.android.sessions.SessionSearch
import sh.roboco.android.sessions.SidebarFixtures
import sh.roboco.android.shell.AppRouter
import uniffi.roboco_core.SearchField
import uniffi.roboco_core.SearchHit

/**
 * The tablet sidebar's goldens (android-ui-parity #105) — the whole
 * [Sidebar] on the ticket's 360 × 800 dp frame (xhdpi) over the gallery's
 * fixture (46's `1-off` sections, a long-Pinned workspace, the fake search
 * answering "tool"), with `topInset = 24`:
 *
 * - `sidebar_rest` — the front page at rest, the field with its
 *   "Search" placeholder under the large title;
 * - `sidebar_current_row` — `currentChatId` on the second Pinned row
 *   ("Model picker catalog sync"), drawn `rowActive`;
 * - `sidebar_scrolled_pinned` — scrolled 104 dp: the band pinned under the
 *   nav row (c = 52), rows scrolling under it;
 * - `sidebar_query_hits` — query "tool" (set unfocused: no caret): the three
 *   header-less hits (the archived one last) and the clear button;
 * - `sidebar_query_no_results` — query "zzz": the empty list + 49's
 *   "No Results" view;
 * - `sidebar_focused_empty` — the field focused and empty: caret +
 *   placeholder, list layout unchanged (focusing changes no layout);
 * - `sidebar_rest_fallback_light` — the ticket's explicit fallback-glass
 *   variant of `sidebar_rest` (identical here: the class forces the
 *   fallback tier, the repo's golden convention — Robolectric blur is not
 *   byte-stable).
 *
 * Recorded by `./gradlew :app:recordRoborazziDebug --tests
 * sh.roboco.android.shell.tablet.SidebarScreenshotTest`, verified by
 * `:app:verifyRoborazziDebug`, then reviewed side by side with an iPad in
 * landscape (the title, the field under it, the pinning, the placeholder,
 * the `rowActive` fill — the ticket's ★ values get one retune there).
 * Paths are relative to `roborazzi { outputDir }`
 * (`app/src/test/screenshots`), landing under `shell/tablet/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class SidebarScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The goldens' fake search: 49's "tool" hits, nothing else. */
    private val fakeSearch: SessionSearch = { q, _ ->
        when (q) {
            "tool" -> SearchFixtures.hits.map { SearchHit(it, 100u, SearchField.TITLE) }
            else -> emptyList()
        }
    }
    private val seenQueries = CopyOnWriteArrayList<String>()

    private val state = SidebarState()
    private val fixtures: SidebarFixtures = sidebarGalleryFixtures { q, n ->
        seenQueries += q
        fakeSearch(q, n)
    }

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    /**
     * Composes the sidebar and captures the whole window. [current] is the
     * current row id; [before] runs the interaction (the scroll, the type)
     * with the frozen clock stepping, and the shot settles first.
     */
    private fun shot(
        appearance: Appearance,
        file: String,
        current: String? = null,
        before: () -> Unit = {},
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    ContextMenuHost {
                        GlassMenuHost(hazeState = rememberHazeState()) {
                            CompositionLocalProvider(LocalSidebarFixtures provides fixtures) {
                                Sidebar(
                                    state = state,
                                    currentChatId = current,
                                    shown = true,
                                    router = AppRouter.Noop,
                                    topInset = 24.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        before()
        compose.onRoot().captureRoboImage("shell/tablet/$file")
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

    /**
     * Sets the query on the field's state, unfocused: no caret, like
     * SearchMorphScreenshotTest's preset text. The search reads the raw
     * text whether or not the field has focus, and the clear button
     * follows non-empty text, so the hits, the "No Results" view and the
     * clear button are the same. Focusing and typing put a blinking caret
     * in the shot, and [awaitTag] polls the async search on wall time (a
     * variable count of 50 ms steps). The blink phase at capture then
     * varied: record run 38607283018's determinism re-verify failed on
     * the caret alone. The focused caret is covered by the
     * sidebar_focused_empty goldens, whose clock steps are fixed.
     */
    private fun query(text: String) {
        compose.runOnUiThread { state.search.setTextAndPlaceCursorAtEnd(text) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- rest

    @Test
    fun restLight() {
        shot(Appearance.Light, "sidebar_rest_light.png")
    }

    @Test
    fun restDark() {
        shot(Appearance.Dark, "sidebar_rest_dark.png")
    }

    @Test
    fun restFallbackLight() {
        // The ticket's explicit fallback-glass variant: byte-identical to
        // sidebar_rest_light here (the class already forces the tier), kept
        // as its own file so a later blur-tier retune has the reference.
        shot(Appearance.Light, "sidebar_rest_fallback_light.png")
    }

    // ----------------------------------------------------- the current row

    @Test
    fun currentRowLight() {
        shot(Appearance.Light, "sidebar_current_row_light.png", current = "chat-models")
    }

    @Test
    fun currentRowDark() {
        shot(Appearance.Dark, "sidebar_current_row_dark.png", current = "chat-models")
    }

    // -------------------------------------------------------- scrolled

    @Test
    fun scrolledPinnedLight() {
        shot(Appearance.Light, "sidebar_scrolled_pinned_light.png") { scroll(104f) }
    }

    @Test
    fun scrolledPinnedDark() {
        shot(Appearance.Dark, "sidebar_scrolled_pinned_dark.png") { scroll(104f) }
    }

    /** Scrolls [distanceDp] forward with the frozen clock stepping. */
    private fun scroll(distanceDp: Float) {
        with(compose.density) {
            compose.runOnUiThread { runBlocking { state.listState.scrollBy(distanceDp.dp.toPx()) } }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    // -------------------------------------------------------- the query

    @Test
    fun queryHitsLight() {
        shot(Appearance.Light, "sidebar_query_hits_light.png") {
            query("tool")
            awaitTag("session-chat-tool-rail")
            compose.mainClock.advanceTimeBy(500) // 41's swap animation settles
            compose.waitForIdle()
        }
    }

    @Test
    fun queryHitsDark() {
        shot(Appearance.Dark, "sidebar_query_hits_dark.png") {
            query("tool")
            awaitTag("session-chat-tool-rail")
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    @Test
    fun queryNoResultsLight() {
        shot(Appearance.Light, "sidebar_query_no_results_light.png") {
            query("zzz")
            awaitTag("search-no-results")
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    @Test
    fun queryNoResultsDark() {
        shot(Appearance.Dark, "sidebar_query_no_results_dark.png") {
            query("zzz")
            awaitTag("search-no-results")
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    // -------------------------------------------------------- focused

    @Test
    fun focusedEmptyLight() {
        shot(Appearance.Light, "sidebar_focused_empty_light.png") {
            compose.onNodeWithTag("sidebar-search").requestFocus()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    @Test
    fun focusedEmptyDark() {
        shot(Appearance.Dark, "sidebar_focused_empty_dark.png") {
            compose.onNodeWithTag("sidebar-search").requestFocus()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
}
