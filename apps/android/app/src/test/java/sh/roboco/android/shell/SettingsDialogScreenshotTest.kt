package sh.roboco.android.shell

import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
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
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The tablet Settings dialog's goldens (android-ui-parity #104), recorded
 * by `recordRoborazziDebug` and verified by `verifyRoborazziDebug`, at the
 * ticket's tablet window (1280 × 800 dp, xhdpi). The entries are inserted
 * with `presentSettledForPreview`, so the captured frame is at rest, never
 * mid-animation; the fixture is the gallery's
 * ([SettingsDialogFakeSplit] over a fake split, the card content built
 * from the gallery's fake root).
 *
 * - `shell/settings_dialog_light.png` / `settings_dialog_dark.png`: the
 *   settled card — 540 × 620 at (370, 90), 38 dp continuous corners,
 *   `#F3F3F5` / `#060606` fill over the split dimmed by black @0.2 (a
 *   sampled `background` pixel outside the card reads ≈ `#C2C2C4` /
 *   `#050505`), the large title and the Done circle in the nav row, the
 *   sidebar at x = 0 (never scaled or clipped);
 * - `shell/settings_dialog_pushed_light.png`: 24's demo detail pushed
 *   inside the card — clipped to it, back circle, no Done;
 * - `shell/settings_dialog_clamped_light.png` (the second config, 560 ×
 *   600): the card clamps to 512 × 552 at (24, 24).
 *
 * `Glass.forceFallback` and Reduce Motion follow the gallery-capture
 * convention (ticket 22), so the goldens stay byte-stable. Paths are
 * relative to `src/test/screenshots`, landing under `shell/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class SettingsDialogScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    private fun shot(appearance: Appearance, file: String, push: Boolean = false) {
        val state = SheetHostState()
        compose.setContent {
            RobocoTheme(appearance) {
                val locals = rememberUpdatedState(currentCompositionLocalContext)
                // Presented settled (idempotent while up): the captured
                // frame is at rest, never mid-animation. The card content
                // is the gallery's fake-root one; the captured locals are
                // this composition's (the theme's).
                state.presentSettledForPreview(SettingsDialogDefaults.Key, SettingsFormSheetStyle) {
                    SettingsDialogContent(
                        scope = this,
                        locals = { locals.value },
                        root = { done -> SettingsDialogFakeRoot(done) },
                        routeScreen = { entry -> NavDemoScreen(entry) },
                    )
                }
                SheetHost(state) { SettingsDialogFakeSplit(state) }
            }
        }
        compose.waitForIdle()
        if (push) {
            // 24's demo detail, pushed inside the card and settled. The
            // semantics scroll needs the clock, so thaw it for the scroll
            // (nothing animates at rest) and freeze it again.
            compose.mainClock.autoAdvance = true
            compose.onNode(scrollableMatcher).performScrollToNode(hasText("Push detail"))
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Push detail").performClick()
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun settledLight() {
        shot(Appearance.Light, "shell/settings_dialog_light.png")
    }

    @Test
    fun settledDark() {
        shot(Appearance.Dark, "shell/settings_dialog_dark.png")
    }

    @Test
    fun pushedLight() {
        shot(Appearance.Light, "shell/settings_dialog_pushed_light.png", push = true)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w560dp-h600dp-xhdpi")
    fun clampedLight() {
        shot(Appearance.Light, "shell/settings_dialog_clamped_light.png")
    }

    /** The card's list scrollable (the LazyColumn is 25's, untagged). */
    private val scrollableMatcher = SemanticsMatcher("has a scroll action") { node ->
        node.config.contains(SemanticsActions.ScrollBy)
    }
}
