package sh.roboco.android.sessions

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
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
 * The Search screen's goldens (android-ui-parity #49): the ticket's four
 * states — `search_recent` (the six-row Recent list under the large
 * "Search" title), `search_hits` (the two active "tool" rows plus the
 * archived hit), `search_no_results` (the system search
 * content-unavailable look, centred between y = 120 and y = 550 dp) and
 * `search_loading` (41's spinner) — light and dark, over the stateless
 * [SearchScreenContent] with a fixed 24 dp status bar and the phone
 * chrome (`BottomChromeInsets(24.dp, 150.dp)`, the gesture-nav worked
 * values).
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp) on the 412 × 700 dp
 * phone; the glyph clock is frozen at 0 ns so the spinner renders its
 * cycle-0 pattern and nothing animates while capturing. Roborazzi paths
 * are relative to `roborazzi { outputDir }` (`src/test/screenshots`), so
 * the goldens land at `src/test/screenshots/sessions/`. Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.SearchScreenScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`, then reviewed side by
 * side with the iOS app's Search tab (title, rows, Recent on empty query)
 * and an iOS 26 system "No Results" capture for the ★ metrics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
class SearchScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Hosts one fixture; the only `setContent` of each test. */
    private fun shot(appearance: Appearance, file: String, results: SearchResults) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    CompositionLocalProvider(LocalBottomChrome provides BottomChromeInsets(24.dp, 150.dp)) {
                        SearchScreenContent(
                            results = results,
                            listState = rememberLazyListState(),
                            onOpenSession = {},
                            topInset = 24.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("sessions/$file")
    }

    // ------------------------------------------------------------- Recent

    @Test
    fun recentLight() {
        shot(Appearance.Light, "search_recent_light.png", SearchFixtures.recentResults)
    }

    @Test
    fun recentDark() {
        shot(Appearance.Dark, "search_recent_dark.png", SearchFixtures.recentResults)
    }

    // --------------------------------------------------------------- Hits

    @Test
    fun hitsLight() {
        shot(Appearance.Light, "search_hits_light.png", SearchFixtures.hitsResults)
    }

    @Test
    fun hitsDark() {
        shot(Appearance.Dark, "search_hits_dark.png", SearchFixtures.hitsResults)
    }

    // --------------------------------------------------------- No results

    @Test
    fun noResultsLight() {
        shot(Appearance.Light, "search_no_results_light.png", SearchFixtures.noResults)
    }

    @Test
    fun noResultsDark() {
        shot(Appearance.Dark, "search_no_results_dark.png", SearchFixtures.noResults)
    }

    // ------------------------------------------------------------ Loading

    @Test
    fun loadingLight() {
        shot(Appearance.Light, "search_loading_light.png", SearchFixtures.loading)
    }

    @Test
    fun loadingDark() {
        shot(Appearance.Dark, "search_loading_dark.png", SearchFixtures.loading)
    }
}
