package sh.roboco.android.composer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.QueueEditGallerySection
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The queue-edit goldens (android-ui-parity #83), recorded by
 * `:app:recordRoborazziDebug --tests 'sh.roboco.android.composer.QueueEditScreenshotTest'`
 * and verified by `:app:verifyRoborazziDebug`: the gallery section's
 * stack **while editing row 1** — after `beginEdit("q1")` has resolved —
 * in light and dark. The frame shows:
 *
 * - the pill `Editing queued message · Tap to cancel` (73's display
 *   overlay, appearing instantly — the non-structural rule);
 * - the queue card with row 1's `Editing` gate (fixture chrome);
 * - the composer with `fix the build` (the row's visible text) and the
 *   `Edit queued message` placeholder;
 * - `+` disabled (the zeron #277 rule).
 *
 * The gallery section composes the real stack (74's container, 82's
 * queue panel, 78's composer bar, 73's pill row) over the
 * [FixtureQueueEditPort]; the test taps its tagged "Edit row 1" chip and
 * advances the clock past the focus morph's spring so the frame settles.
 *
 * `ReduceMotion.debugOverride = true` and `Glass.forceFallback = true`
 * (the golden convention; Robolectric blur is not byte-stable), reset in
 * `@After`. Paths are relative to `src/test/screenshots`, landing under
 * `composer/`: `queue-edit-editing-light.png` /
 * `queue-edit-editing-dark.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class QueueEditScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeMotionAndForceFallbackTier() {
        ReduceMotion.debugOverride = true
        Glass.forceFallback = true
        compose.mainClock.autoAdvance = false
    }

    @After
    fun restore() {
        ReduceMotion.debugOverride = null
        Glass.forceFallback = false
    }

    // ----------------------------------------------------------- goldens

    @Test
    fun queueEditEditingLight() {
        shot(Appearance.Light)
    }

    @Test
    fun queueEditEditingDark() {
        shot(Appearance.Dark)
    }

    private fun shot(appearance: Appearance) {
        compose.setContent {
            RobocoTheme(appearance) {
                QueueEditGallerySection()
            }
        }
        compose.waitForIdle()

        // Begin the edit: the chip's click launches the begin coroutine on
        // the composition's scope, which runs on the frame clock. Two
        // frames let it run and the state writes land; then the clock
        // settles the focus morph's spring.
        compose.onNodeWithTag("queue-edit-edit-1").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val mode = if (appearance == Appearance.Dark) "dark" else "light"
        compose.onRoot().captureRoboImage("composer/queue-edit-editing-$mode.png")
    }
}
