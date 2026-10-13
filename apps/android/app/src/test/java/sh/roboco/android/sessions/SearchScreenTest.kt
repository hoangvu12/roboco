package sh.roboco.android.sessions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * The Search screen's behaviour (android-ui-parity #49): the four
 * [SearchResults] states through the stateless [SearchScreenContent] —
 * Robolectric + `createAndroidComposeRule` at `w412dp-h700dp-xxhdpi`
 * (1 dp = 3 px), the goldens' frame, with a fixed 24 dp status bar (Robolectric
 * reports no insets) and the phone chrome the shell provides
 * (`BottomChromeInsets(24.dp, 150.dp)`, gesture nav's worked values).
 *
 * One `setContent` per test (the host flips the fixture parameter); the
 * glyph clock is frozen at 0 ns so the loading spinner renders its
 * cycle-0 pattern without a frame loop; `onOpenSession` is a recording
 * lambda (the stateful screen's IME-hide wrapper and the push are 24/29/32
 * behaviour, tested in their own tickets).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
class SearchScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = CopyOnWriteArrayList<String>()

    /** Hosts one fixture; the only `setContent` of each test. */
    private fun host(results: SearchResults) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    CompositionLocalProvider(LocalBottomChrome provides BottomChromeInsets(24.dp, 150.dp)) {
                        SearchScreenContent(
                            results = results,
                            listState = rememberLazyListState(),
                            onOpenSession = { opened += it },
                            topInset = 24.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- helpers

    /** px of [v] dp at the test density (3). */
    private fun dp(v: Float): Float = with(compose.density) { v.dp.toPx() }

    /** `getBoundsInRoot` is a [androidx.compose.ui.unit.DpRect]; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect =
        with(compose.density) { getBoundsInRoot().toRect() }

    // -------------------------------------------------------------- Recent

    @Test
    fun theRecentFixtureShowsSixRowsNoHeaderAndNoNoResults() {
        host(SearchFixtures.recentResults)
        for (id in SearchFixtures.recent.map { it.id }) {
            compose.onNodeWithTag("session-$id").assertExists()
        }
        // No "Recent" label, no count, no no-results view — Search never
        // shows section headers.
        compose.onNodeWithText("Recent").assertDoesNotExist()
        compose.onNodeWithTag("search-no-results").assertDoesNotExist()
    }

    @Test
    fun anEmptyRecentListStaysBlankWithNoNoResultsView() {
        host(SearchResults(SearchResults.Mode.Recent, "", emptyList(), emptySet()))
        compose.onNodeWithTag("search-no-results").assertDoesNotExist()
        compose.onNodeWithTag("sessions-empty").assertDoesNotExist()
        compose.onNodeWithTag("sessions-loading").assertDoesNotExist()
    }

    // -------------------------------------------------------------- Hits

    @Test
    fun theHitsFixtureShowsTheRowsInCoreOrderIncludingTheArchivedHit() {
        host(SearchFixtures.hitsResults)
        for (id in SearchFixtures.hits.map { it.id }) {
            compose.onNodeWithTag("session-$id").assertExists()
        }
        compose.onNodeWithTag("search-no-results").assertDoesNotExist()
    }

    @Test
    fun tappingARowCallsOnOpenSessionWithItsIdOnce() {
        host(SearchFixtures.hitsResults)
        compose.onNodeWithTag("session-chat-tool-colors").performClick()
        compose.waitForIdle()
        assertEquals(listOf("chat-tool-colors"), opened)
    }

    // ----------------------------------------------------------- Loading

    @Test
    fun theLoadingFixtureShows41sSpinnerAndNeitherRowsNorNoResults() {
        host(SearchFixtures.loading)
        compose.onNodeWithTag("sessions-loading").assertExists()
        compose.onNodeWithTag("session-chat-veil").assertDoesNotExist()
        compose.onNodeWithTag("session-chat-tool-colors").assertDoesNotExist()
        compose.onNodeWithTag("search-no-results").assertDoesNotExist()
    }

    // --------------------------------------------------------- No results

    @Test
    fun theNoResultsFixtureShowsTheSystemSearchCopyAsAPoliteLiveRegion() {
        host(SearchFixtures.noResults)
        val node = compose.onNodeWithTag("search-no-results")
        node.assertExists()
        node.assert(hasText("No Results"))
        node.assert(hasText("Check the spelling or try a new search."))
        compose.onNodeWithText("No Results").assertExists()
        compose.onNodeWithText("Check the spelling or try a new search.").assertExists()
        assertEquals(LiveRegionMode.Polite, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))

        // Centred in the free area: y 120 (24 + 44 + 52) … 550 (700 − 150),
        // so the copy never sits under the raised field's band.
        val b = node.pxBounds()
        assertEquals(dp(412f / 2f), b.center.x, 1f)
        assertEquals(dp(120f + (550f - 120f) / 2f), b.center.y, 1f)
        assertTrue("top below the expanded large title", b.top >= dp(120f) - 1f)
        assertTrue("bottom above the bottom padding", b.bottom <= dp(550f) + 1f)
        // The list behind the view is empty but present.
        compose.onNodeWithTag("session-chat-tool-colors").assertDoesNotExist()
    }

    // ------------------------------------------------------------ the title

    @Test
    fun theSearchTitleIsAHeading() {
        host(SearchFixtures.recentResults)
        val title = compose.onNodeWithTag("nav-large-title")
        title.assert(hasText("Search"))
        assertTrue(
            "the large title is a heading (25)",
            title.fetchSemanticsNode().config.contains(SemanticsProperties.Heading),
        )
        // No trailing items and no back circle: the root draws an empty nav
        // row (25) — nothing of this screen's own.
        compose.onNodeWithTag("nav-back").assertDoesNotExist()
    }
}
