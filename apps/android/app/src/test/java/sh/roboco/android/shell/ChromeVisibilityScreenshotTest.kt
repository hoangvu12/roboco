package sh.roboco.android.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.sessions.LocalSidebarFixtures
import sh.roboco.android.shell.tablet.settledEmptySessionsFixtures

/**
 * Ticket 30's goldens: the whole [MainTabShell] chrome group — pill,
 * search, accessory — at 402 × 874 dp, light and dark, density 3,
 * `LiveCounts(working = 0, awaiting = 1)` so the accessory reads
 * "1 needs you":
 *
 *  - `chrome_idle` — the resting shell (the placeholder root list under
 *    the group, its bottom inset now #30's content padding);
 *  - `chrome_push_050` — a push to a hiding route frozen at 0.5: the group
 *    rides with the root (−0.15 W), cut at the incoming screen's leading
 *    edge, dimmed by the scrim;
 *  - `chrome_pop_025` — a pop from a hiding route frozen at 0.25: the
 *    group laid out at its final place, its **content** at 25 % on full
 *    glass (the pop reveal);
 *  - `chrome_reduced_pop_050` — the same pop under Reduce Motion: the
 *    group cross-fades (alpha 0.5), no ride, no clip, no dim.
 *
 * The frozen states are built with 24's `holdHostlessOps()` + `freeze()`
 * before composition (without the hold the op completes synchronously and
 * `freeze` throws); `routeContent` renders 24's [NavDemoScreen] for the
 * pushed pages so the covering screen is visible. Recorded by
 * `:app:recordRoborazziDebug` and verified by `:app:verifyRoborazziDebug`;
 * the files land under `src/test/screenshots/shell/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class ChromeVisibilityScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    private fun shellShot(file: String, state: MainTabState, reduceMotion: Boolean = false) {
        if (reduceMotion) ReduceMotion.debugOverride = true
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                // Ticket 46: the Sessions root is the real screen now — its
                // ⋯ needs a GlassMenuHost (the golden's background rows
                // change accordingly; re-record with recordRoborazziDebug).
                GlassMenuHost {
                    // The Sessions root at its settled empty state: the live
                    // FrontPageModel's first load races the capture on wall
                    // time (spinner vs "No sessions yet"; record run
                    // 38607283018 tripped the gallery's copy of this shell).
                    CompositionLocalProvider(LocalSidebarFixtures provides settledEmptySessionsFixtures()) {
                        MainTabShell(
                            state = state,
                            counts = LiveCounts(working = 0, awaiting = 1),
                            routeContent = { entry ->
                                if (entry.route is TabRootRoute) ShellRouteContent(entry.route) else NavDemoScreen(entry)
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("shell/$file")
    }

    private fun idleState(): MainTabState = MainTabState(TabId.Sessions)

    /** A push to a hiding route, frozen at [progress] before composition. */
    private fun frozenPush(progress: Float): MainTabState =
        MainTabState(TabId.Sessions).apply {
            val s = stack(TabId.Sessions)
            s.holdHostlessOps()
            s.push(DemoRoute("Session", hides = true))
            s.freeze(progress)
        }

    /** A pop from a hiding route, frozen at [progress] before composition. */
    private fun frozenPop(progress: Float): MainTabState =
        MainTabState(TabId.Sessions).apply {
            val s = stack(TabId.Sessions)
            s.push(DemoRoute("Session", hides = true)) // completes hostlessly
            s.holdHostlessOps()
            s.pop()
            s.freeze(progress)
        }

    // --------------------------------------------------------------- idle

    @Test
    fun chromeIdleLight() {
        shellShot("chrome_idle_light.png", idleState())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun chromeIdleDark() {
        shellShot("chrome_idle_dark.png", idleState())
    }

    // --------------------------------------------------------- push to 0.5

    @Test
    fun chromePush050Light() {
        shellShot("chrome_push_050_light.png", frozenPush(0.5f))
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun chromePush050Dark() {
        shellShot("chrome_push_050_dark.png", frozenPush(0.5f))
    }

    // ---------------------------------------------------------- pop at 0.25

    @Test
    fun chromePop025Light() {
        shellShot("chrome_pop_025_light.png", frozenPop(0.25f))
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-night-xxhdpi")
    fun chromePop025Dark() {
        shellShot("chrome_pop_025_dark.png", frozenPop(0.25f))
    }

    // ------------------------------------------------------ reduced-motion pop

    @Test
    fun chromeReducedPop050Light() {
        shellShot("chrome_reduced_pop_050_light.png", frozenPop(0.5f), reduceMotion = true)
    }
}
