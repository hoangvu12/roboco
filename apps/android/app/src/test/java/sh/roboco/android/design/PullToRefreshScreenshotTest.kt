package sh.roboco.android.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.shell.LargeTitleScaffold

/**
 * The pull-to-refresh goldens (android-ui-parity #48): the 21-dp cell
 * spinner in the gap of a real [LargeTitleScaffold] — held at 60 dp while
 * refreshing (alpha 1) and at 40 dp while pulling (alpha 0.5) — in light
 * and dark, on the ticket's `w412dp-h700dp-xxhdpi` with `topInset = 24`
 * (navBottom 68, the gap 68…128 / 68…108, the spinner centred at y 98 / 88).
 *
 * The clock is pinned and the glyph clock frozen at 0 ns (cycle-0 cell
 * pattern — no frame loop, byte-stable goldens). The refreshing fixture
 * uses a never-returning fake, so the spinner holds at the 60-dp gap; the
 * pulling fixture pins `offset = 40 dp` with the phase still Idle. The
 * background around the spinner is the scaffold background — no indicator
 * disc, no shadow; the rows below ride the gap.
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.design.PullToRefreshScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`. Paths are relative
 * to the `roborazzi { outputDir }` (`src/test/screenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PullToRefreshScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var stateRef: PullToRefreshState? = null

    private fun shot(appearance: Appearance, file: String, prepare: (PullToRefreshState) -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(appearance) {
                // Frozen at 0 ns: the spinner renders the cycle-0 pattern
                // and the capture never starts a frame loop.
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    val palette = RobocoTheme.palette
                    val typeScale = LocalTypeScale.current
                    val state = rememberPullToRefreshState(onRefresh = { delay(60_000) })
                    stateRef = state
                    LargeTitleScaffold(
                        title = "Sessions",
                        topInset = 24.dp,
                        showBack = false,
                        onBack = {},
                        pullToRefresh = state,
                    ) {
                        items(20) { index ->
                            Box(Modifier.fillMaxWidth().height(64.dp)) {
                                BasicText(
                                    "Row $index",
                                    style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        prepare(stateRef!!)
        // A frozen-clock write from the test thread reaches the recomposer only
        // through the apply notification (advanceTimeBy never sends it).
        Snapshot.sendApplyNotifications()
        // One frame's worth of virtual time: the recomposition that composes
        // the indicator (and the 60 → 60 settle) lands; the never-returning
        // fake keeps the phase at Refreshing.
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
    fun refreshingLight() {
        shot(Appearance.Light, "design/pull_to_refresh_refreshing_light.png") { it.startRefresh() }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
    fun refreshingDark() {
        shot(Appearance.Dark, "design/pull_to_refresh_refreshing_dark.png") { it.startRefresh() }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
    fun pullingLight() {
        // 40 dp in px: the gap at 40, the phase Idle, alpha 0.5.
        shot(Appearance.Light, "design/pull_to_refresh_pulling_light.png") { state ->
            state.setOffsetForTest(with(compose.density) { 40.dp.toPx() })
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
    fun pullingDark() {
        shot(Appearance.Dark, "design/pull_to_refresh_pulling_dark.png") { state ->
            state.setOffsetForTest(with(compose.density) { 40.dp.toPx() })
        }
    }
}
