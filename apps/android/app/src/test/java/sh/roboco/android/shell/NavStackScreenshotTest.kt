package sh.roboco.android.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
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

/**
 * Ticket 24's goldens: the push at 0.5 (light, dark, RTL), the pop at 0.25
 * with the chrome riding the revealed root, and the reduced cross-fade at
 * 0.5. Fixture: `w360dp-h640dp-xxhdpi`, demo routes "Sessions" → "Session"
 * (hides chrome), the fake chrome, stack built with `holdHostlessOps()` then
 * `freeze(...)`. The goldens are recorded by the batch run
 * (`:app:recordRoborazziDebug`) and land under `src/test/screenshots/shell/`.
 *
 * RTL is driven through `LocalLayoutDirection` (the geometry reads it), which
 * is deterministic regardless of Robolectric's qualifier parsing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class NavStackScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    /** A Sessions → Session stack frozen mid-push at [progress]. */
    private fun frozenPush(progress: Float): NavStack =
        NavStack(DemoRoute("Sessions")).apply {
            holdHostlessOps()
            push(DemoRoute("Session", hides = true))
            freeze(progress)
        }

    private fun capture(stack: NavStack, fileName: String, rtl: Boolean = false) {
        compose.setContent {
            RobocoTheme {
                // RTL is driven through LocalLayoutDirection (the geometry
                // reads it), deterministic regardless of Robolectric's
                // qualifier parsing.
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Host(stack)
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("shell/$fileName")
    }

    @Composable
    private fun Host(stack: NavStack) {
        NavStackHost(
            stack = stack,
            active = true,
            bottomChrome = { NavFakeChrome() },
        ) { entry -> NavDemoScreen(entry) }
    }

    @Test
    fun navPush050Light() {
        capture(frozenPush(0.5f), "nav_push_050_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h640dp-night-xxhdpi")
    fun navPush050Dark() {
        capture(frozenPush(0.5f), "nav_push_050_dark.png")
    }

    @Test
    fun navPush050Rtl() {
        capture(frozenPush(0.5f), "nav_push_050_rtl.png", rtl = true)
    }

    @Test
    fun navPop025Light() {
        // A pop from the session back to the list, frozen at 0.25: the chrome
        // rides the revealed root, dimmed under the scrim and covered by the
        // outgoing session.
        val stack = NavStack(DemoRoute("Sessions")).apply {
            holdHostlessOps()
            push(DemoRoute("Session", hides = true))
            beginInteractivePop()!!
            freeze(0.25f)
        }
        capture(stack, "nav_pop_025_light.png")
    }

    @Test
    fun navReduced050Light() {
        ReduceMotion.debugOverride = true
        capture(frozenPush(0.5f), "nav_reduced_050_light.png")
    }
}
