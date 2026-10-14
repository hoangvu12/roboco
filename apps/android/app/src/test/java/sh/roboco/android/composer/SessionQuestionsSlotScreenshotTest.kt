package sh.roboco.android.composer

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The question **panel behaviour** goldens (android-ui-parity #91),
 * recorded by `:app:recordRoborazziDebug` and verified by
 * `:app:verifyRoborazziDebug`, in light and dark:
 *
 *  - `question_behaviour_f2_picked` — F2 page 1 with option 1 picked,
 *    before the auto-advance timer fires (the fixture state's
 *    `autoAdvanceMs = Long.MAX_VALUE`): the same page ticket 90's B1
 *    golden draws, but driven through the real
 *    [SessionQuestionsSlot] and [QuestionPanelState];
 *  - `question_behaviour_fade_mid` — the same state 100 ms into the
 *    200 ms page cross-dissolve after Next: both pages in the tree, the
 *    container already snapped to page 2's height, neither page fully
 *    opaque.
 *
 * Each sits in its own 388 dp [GlassStage] over the striped backdrop at
 * the stage height ticket 90 uses for B1 (the card is the backdrop's
 * sibling, never inside its `hazeSource`).
 * `ReduceMotion.debugOverride = true` and `Glass.forceFallback = true`
 * (the GalleryCapture convention; Robolectric blur is not
 * byte-stable), reset in `@After`. The frozen-clock fade capture
 * follows the ActionSheetHost convention (`runOnUiThread` +
 * `Snapshot.sendApplyNotifications()` + manual frames).
 *
 * Paths are relative to `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `composer/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w388dp-h960dp-xhdpi")
class SessionQuestionsSlotScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeMotionAndForceFallbackTier() {
        ReduceMotion.debugOverride = true
        Glass.forceFallback = true
    }

    @After
    fun restore() {
        ReduceMotion.debugOverride = null
        Glass.forceFallback = false
        compose.mainClock.autoAdvance = true
    }

    private fun mode(appearance: Appearance): String = if (appearance == Appearance.Dark) "dark" else "light"

    private fun mount(appearance: Appearance): QuestionPanelState {
        val state = questionFixtureState(CoroutineScope(Dispatchers.Main.immediate))
        compose.setContent {
            RobocoTheme(appearance) {
                QuestionPanelStage(state, height = 480.dp)
            }
        }
        return state
    }

    // ------------------------------------------------------------- goldens

    @Test
    fun f2PickedLight() {
        mount(Appearance.Light)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/question_behaviour_f2_picked_${mode(Appearance.Light)}.png")
    }

    @Test
    fun f2PickedDark() {
        mount(Appearance.Dark)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/question_behaviour_f2_picked_${mode(Appearance.Dark)}.png")
    }

    @Test
    fun fadeMidLight() {
        fadeMid(Appearance.Light)
    }

    @Test
    fun fadeMidDark() {
        fadeMid(Appearance.Dark)
    }

    /** F2 at +100 ms of the page fade, captured with the clock frozen. */
    private fun fadeMid(appearance: Appearance) {
        val state = mount(appearance)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100) // settle the first composition
        compose.runOnUiThread { state.next() } // page 0 is answered by the fixture's pick
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // frame 0: both pages composed
        compose.mainClock.advanceTimeBy(100) // +100 ms of the 200 ms cross-dissolve
        compose.onRoot().captureRoboImage("composer/question_behaviour_fade_mid_${mode(appearance)}.png")
    }
}
