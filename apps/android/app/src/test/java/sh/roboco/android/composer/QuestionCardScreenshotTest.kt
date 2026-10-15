package sh.roboco.android.composer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.hazeSource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.gallery.QuestionCardFixtures
import sh.roboco.android.design.gallery.QuestionCardStages
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The question-card goldens (android-ui-parity #90), recorded by
 * `:app:recordRoborazziDebug` and verified by `:app:verifyRoborazziDebug`:
 * the ticket's seven fixtures — A, B1, B2, B3, C, D and E — in light and
 * dark, each in its own 388 dp [GlassStage] over the striped backdrop at
 * the stage height the gallery uses ([QuestionCardStages], shared so the
 * two never diverge; the card is the backdrop's sibling, never inside its
 * `hazeSource`).
 *
 * `ReduceMotion.debugOverride = true` and `Glass.forceFallback = true`
 * (the GalleryCapture convention; Robolectric blur is not byte-stable),
 * reset in `@After`. Nothing animates — every model is a fixed value.
 *
 * Paths are relative to `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `composer/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w388dp-h960dp-xhdpi")
class QuestionCardScreenshotTest {

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
    }

    private fun shot(appearance: Appearance, key: String) {
        val stage = QuestionCardStages.first { it.key == key }
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideHazeState {
                    GlassStage(
                        modifier = Modifier.width(388.dp),
                        height = stage.height,
                        backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
                    ) {
                        QuestionCard(stage.model, QuestionCardFixtures.NoActions, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        compose.waitForIdle()
        val mode = if (appearance == Appearance.Dark) "dark" else "light"
        compose.onRoot().captureRoboImage("composer/question_card_${key}_$mode.png")
    }

    // ----------------------------------------------------------- goldens

    @Test
    fun aLight() = shot(Appearance.Light, "a")

    @Test
    fun aDark() = shot(Appearance.Dark, "a")

    @Test
    fun b1Light() = shot(Appearance.Light, "b1")

    @Test
    fun b1Dark() = shot(Appearance.Dark, "b1")

    @Test
    fun b2Light() = shot(Appearance.Light, "b2")

    @Test
    fun b2Dark() = shot(Appearance.Dark, "b2")

    @Test
    fun b3Light() = shot(Appearance.Light, "b3")

    @Test
    fun b3Dark() = shot(Appearance.Dark, "b3")

    @Test
    fun cLight() = shot(Appearance.Light, "c")

    @Test
    fun cDark() = shot(Appearance.Dark, "c")

    @Test
    fun dLight() = shot(Appearance.Light, "d")

    @Test
    fun dDark() = shot(Appearance.Dark, "d")

    @Test
    fun eLight() = shot(Appearance.Light, "e")

    @Test
    fun eDark() = shot(Appearance.Dark, "e")
}
