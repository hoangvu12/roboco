package sh.roboco.android.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The bottom stack's goldens (android-ui-parity #74): the ticket's
 * fixture — [BottomStackStandIn], the container over the striped
 * transcript stand-in with glass stand-in slots and ticket 08's jump
 * circle — in light and dark, at `w412dp-h915dp-xxhdpi` with the 24 dp
 * nav bottom faked through the debug `imeOverride` (the tablet case
 * overrides the window with a method-level qualifier):
 *
 * - `bottomStack_composerOnly` — case A: the composer alone, the fade
 *   clear 44 dp above the stack;
 * - `bottomStack_pillQueueComposer` — case B with a two-row queue;
 * - `bottomStack_questions` — the question panel replacing composer and
 *   queue;
 * - `bottomStack_keyboard300` — case C: the stack riding a 300 dp IME;
 * - `bottomStack_tablet1280` — case D: 1280 × 800, the stack capped at
 *   768 and centred.
 *
 * Static positions (the resting model, the glass fallback tier, the
 * unanimated first render) so nothing animates while capturing. Recorded
 * by `recordRoborazziDebug`, verified by `verifyRoborazziDebug`; paths
 * are relative to `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class SessionBottomStackScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
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

    // -------------------------------------------------------------- goldens

    @Test
    fun bottomStackComposerOnlyLight() {
        shot(Appearance.Light, "BottomStack_composerOnly_light.png") {
            BottomStackStandIn(model = BottomStackModel(), imeOverride = 24.dp)
        }
    }

    @Test
    fun bottomStackComposerOnlyDark() {
        shot(Appearance.Dark, "BottomStack_composerOnly_dark.png") {
            BottomStackStandIn(model = BottomStackModel(), imeOverride = 24.dp)
        }
    }

    @Test
    fun bottomStackPillQueueComposerLight() {
        shot(Appearance.Light, "BottomStack_pillQueueComposer_light.png") {
            BottomStackStandIn(model = BottomStackModel(queueCount = 2, bannerShown = true), imeOverride = 24.dp)
        }
    }

    @Test
    fun bottomStackPillQueueComposerDark() {
        shot(Appearance.Dark, "BottomStack_pillQueueComposer_dark.png") {
            BottomStackStandIn(model = BottomStackModel(queueCount = 2, bannerShown = true), imeOverride = 24.dp)
        }
    }

    @Test
    fun bottomStackQuestionsLight() {
        shot(Appearance.Light, "BottomStack_questions_light.png") {
            BottomStackStandIn(model = BottomStackModel(questionsShown = true, queueCount = 2), imeOverride = 24.dp)
        }
    }

    @Test
    fun bottomStackQuestionsDark() {
        shot(Appearance.Dark, "BottomStack_questions_dark.png") {
            BottomStackStandIn(model = BottomStackModel(questionsShown = true, queueCount = 2), imeOverride = 24.dp)
        }
    }

    @Test
    fun bottomStackKeyboard300Light() {
        shot(Appearance.Light, "BottomStack_keyboard300_light.png") {
            BottomStackStandIn(model = BottomStackModel(), imeOverride = 300.dp)
        }
    }

    @Test
    fun bottomStackKeyboard300Dark() {
        shot(Appearance.Dark, "BottomStack_keyboard300_dark.png") {
            BottomStackStandIn(model = BottomStackModel(), imeOverride = 300.dp)
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w1280dp-h800dp-xxhdpi")
    fun bottomStackTablet1280Light() {
        shot(Appearance.Light, "BottomStack_tablet1280_light.png") {
            BottomStackStandIn(
                model = BottomStackModel(),
                imeOverride = 48.dp,
                frameWidth = 1280.dp,
                frameHeight = 800.dp,
            )
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w1280dp-h800dp-xxhdpi")
    fun bottomStackTablet1280Dark() {
        shot(Appearance.Dark, "BottomStack_tablet1280_dark.png") {
            BottomStackStandIn(
                model = BottomStackModel(),
                imeOverride = 48.dp,
                frameWidth = 1280.dp,
                frameHeight = 800.dp,
            )
        }
    }

    // -------------------------------------------------------- pixel probes

    /**
     * The fade is visibly solid behind the composer and clear 44 dp above
     * the stack: left of the stack (x 6) the fade is the only thing over
     * the stripes, so a probe at the composer's lower half reads the solid
     * background (fade alpha 1 past 55 % of its height), and one 14 dp
     * above the fade's top still sees a stripe.
     */
    @Test
    fun theFadeIsSolidAtTheComposerAndClear44Above() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                BottomStackStandIn(model = BottomStackModel(), imeOverride = 24.dp)
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val px = compose.onRoot().captureToImage().toPixelMap()
        val d = compose.density.density
        val background = Palette.of(false).background
        // Fade top 789, height 126: y 870 is at (870 − 789) / 126 = 64 %,
        // solid. Composer spans 833..883 at x 12..400, so x 6 misses it.
        val solid = px[(6f * d).toInt(), (870f * d).toInt()]
        assertTrue("solid behind the composer: $solid vs $background", colorDistance(solid, background) < 0.06f)
        // y 775 is 14 dp above the fade's top: a stripe shows through.
        val clear = px[(6f * d).toInt(), (775f * d).toInt()]
        assertTrue("a stripe shows above the fade: $clear", colorDistance(clear, background) > 0.1f)
    }

    /** The max channel distance between two colours (pixel probes). */
    private fun colorDistance(a: Color, b: Color): Float =
        max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))
}
