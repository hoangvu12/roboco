package sh.roboco.android.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.shell.DemoRoute
import sh.roboco.android.shell.NavStack

/**
 * The push/pop choreography's goldens (android-ui-parity #85): the
 * gallery's frame (402 × 874) over a stack built with
 * `holdHostlessOps()` and frozen with 24's `freeze(...)`, so nothing
 * animates while capturing:
 *
 * - `session_push_030` — an animated push frozen at 0.3: the stack
 *   capsule at 30 % over the page, the jump button at full (forced)
 *   visibility;
 * - `session_pop_050` — a pop frozen at 0.5: the stack and the jump at
 *   50 %;
 * - `session_reduced_pop_050` — a reduced-motion pop at 0.5: the stack
 *   multiplier 1, only 24's layer cross-fade.
 *
 * Recorded by `recordRoborazziDebug`, verified by `verifyRoborazziDebug`;
 * paths are relative to `src/test/screenshots`. The pixel probe pins the
 * multiplier itself: the capsule's colour at push 0.3 is 30 % of the way
 * from the page background to the (fallback) glass fill.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class SessionNavScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlassAndReduceMotion() {
        Glass.forceFallback = false
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    private fun shot(appearance: Appearance, file: String, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().captureRoboImage("session/$file")
    }

    /**
     * The frozen-stack golden body: [build] makes the held stack (called
     * inside `remember`, before any host exists — `freeze` needs the
     * op's transition still waiting for a host).
     */
    private fun shotStack(appearance: Appearance, file: String, build: NavStack.() -> Unit) {
        shot(appearance, file) {
            val stack = remember { NavStack(DemoRoute("Sessions")).apply(build) }
            SessionNavGalleryFrame(stack) { SessionNavFixtureScreen() }
        }
    }

    // -------------------------------------------------------------- goldens

    @Test
    fun pushAt030Light() {
        shotStack(Appearance.Light, "session_push_030_light.png") {
            holdHostlessOps()
            push(GallerySessionRoute())
            freeze(0.3f)
        }
    }

    @Test
    fun pushAt030Dark() {
        shotStack(Appearance.Dark, "session_push_030_dark.png") {
            holdHostlessOps()
            push(GallerySessionRoute())
            freeze(0.3f)
        }
    }

    @Test
    fun popAt050Light() {
        shotStack(Appearance.Light, "session_pop_050_light.png") {
            holdHostlessOps()
            push(GallerySessionRoute(), animated = false)
            pop()
            freeze(0.5f)
        }
    }

    @Test
    fun popAt050Dark() {
        shotStack(Appearance.Dark, "session_pop_050_dark.png") {
            holdHostlessOps()
            push(GallerySessionRoute(), animated = false)
            pop()
            freeze(0.5f)
        }
    }

    @Test
    fun reducedPopAt050Light() {
        ReduceMotion.debugOverride = true
        shotStack(Appearance.Light, "session_reduced_pop_050_light.png") {
            holdHostlessOps()
            push(GallerySessionRoute(), animated = false)
            pop()
            freeze(0.5f)
        }
    }

    /**
     * The multiplier is really applied: at a push frozen at 0.3, a pixel
     * inside the capsule (centre, 25 dp up from the frame's bottom) reads
     * 30 % of the way from the page background towards the capsule's
     * full-α colour over the page — the glass fallback fill (no haze
     * source, `forceFallback`), captured from an idle session. The second
     * tree is the idle session, swapped in through [idle] — one setContent
     * per test (a second throws; TypeFamiliesTest recreates the activity
     * first), each tree with its own stack.
     */
    @Test
    fun theCapsuleBlendsAtThirtyPercentUnderAPushFrozenAt030() {
        var idle by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                if (!idle) {
                    val stack = remember {
                        NavStack(DemoRoute("Sessions")).apply {
                            holdHostlessOps()
                            push(GallerySessionRoute())
                            freeze(0.3f)
                        }
                    }
                    SessionNavGalleryFrame(stack) { SessionNavFixtureScreen() }
                } else {
                    // An idle SESSION on top (a hostless push completes at
                    // once): the root alone is NavDemoScreen, which has no
                    // capsule, so "full" would read the plain background.
                    val stack = remember {
                        NavStack(DemoRoute("Sessions")).apply { push(GallerySessionRoute(), animated = false) }
                    }
                    SessionNavGalleryFrame(stack) { SessionNavFixtureScreen() }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val d = compose.density.density
        val background = Palette.of(false).background
        // The capsule's centre at idle: x = 201 dp, y = 874 − 8 − 25 = 841 dp.
        // At the push frozen at 0.3 the session page (and the capsule with
        // it) is still 70 % off-frame: aboveTx = (1 − p)·w ≈ 281 dp, so the
        // capsule's visible sliver starts at ≈ 293 dp — probe x = 350 dp
        // (page-x ≈ 69, inside the capsule).
        val blended = px((350f * d).toInt(), (841f * d).toInt())

        // The full-α capsule over the page: an idle session (no
        // transition → α 1); the empty content means the surface is the
        // fill itself.
        compose.runOnUiThread { idle = true }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val full = px((201f * d).toInt(), (841f * d).toInt())

        // α 0.3: the blended colour sits 30 % (± a fat 12 % for the glass
        // shadow and rounding) of the way from background to full.
        val t = blendFraction(blended, background, full)
        assertTrue("the capsule should blend at ~0.3, was $t (blended=$blended full=$full)", t in 0.18f..0.42f)
    }

    /** Reads one pixel of the current root capture. */
    private fun px(x: Int, y: Int) = compose.onRoot().captureToImage().toPixelMap()[x, y]

    /**
     * Where `c` sits on the `from → to` run of each channel (max channel
     * distance); 0 when `c == from`, 1 when `c == to`.
     */
    private fun blendFraction(c: Color, from: Color, to: Color): Float {
        val num = max(abs(c.red - from.red), max(abs(c.green - from.green), abs(c.blue - from.blue)))
        val den = max(abs(to.red - from.red), max(abs(to.green - from.green), abs(to.blue - from.blue)))
        if (den < 1e-3f) return 0f
        return (num / den).coerceIn(0f, 1f)
    }
}
