package sh.roboco.android.design.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme

/**
 * The glass-button goldens (ticket 08), recorded by `recordRoborazziDebug`
 * and verified by `verifyRoborazziDebug`: every variant group on the fixed
 * striped backdrop ([GlassStage]'s deterministic [GalleryBackdrop]) in
 * light and dark, on the ticket's 360 × 640 dp device at density 3.
 *
 * `Glass.forceFallback = true` for the whole class so the goldens never
 * depend on how Robolectric reports hardware acceleration
 * (the `GlassSurfaceScreenshotTest` convention): the glass variants draw
 * the fallback fill, rim and shadow. The clock is frozen for every shot:
 * the busy spinner sits at step 0, and the pressed shot advances exactly
 * 400 ms so every button is settled at `Glass.PressScale`.
 *
 * Paths are relative to the `roborazzi { outputDir }`
 * (`src/test/screenshots`), landing under `design/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class GlassButtonsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    private fun shot(appearance: Appearance, file: String, content: @Composable BoxScope.() -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideHazeState {
                    GlassStage(
                        modifier = Modifier.fillMaxSize(),
                        height = 640.dp,
                        backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
                        content = content,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun circlesLight() {
        shot(Appearance.Light, "design/glass_buttons_circles_light.png") { GlassCircleVariants() }
    }

    @Test
    fun circlesDark() {
        shot(Appearance.Dark, "design/glass_buttons_circles_dark.png") { GlassCircleVariants() }
    }

    @Test
    fun textCapsulesLight() {
        shot(Appearance.Light, "design/glass_buttons_text_capsules_light.png") { GlassTextCapsuleVariants() }
    }

    @Test
    fun textCapsulesDark() {
        shot(Appearance.Dark, "design/glass_buttons_text_capsules_dark.png") { GlassTextCapsuleVariants() }
    }

    // Includes the busy Use button; the frozen clock keeps its spinner at
    // step 0 (the busy golden's convention from the ticket).
    @Test
    fun prominentLight() {
        shot(Appearance.Light, "design/glass_buttons_prominent_light.png") { GlassProminentVariants() }
    }

    @Test
    fun prominentDark() {
        shot(Appearance.Dark, "design/glass_buttons_prominent_dark.png") { GlassProminentVariants() }
    }

    /**
     * Each kind held via a `PressInteraction.Press` emitted into its
     * interaction source, clock advanced 400 ms: the glass circle and the
     * capsule swell through their interactive [GlassSurface], the filled
     * circle and the prominent through [Modifier.glassPress].
     */
    private fun pressedShot(appearance: Appearance, file: String) {
        val glassCircle = MutableInteractionSource()
        val filledCircle = MutableInteractionSource()
        val capsule = MutableInteractionSource()
        val prominent = MutableInteractionSource()
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideHazeState {
                    GlassStage(
                        modifier = Modifier.fillMaxSize(),
                        height = 640.dp,
                        backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            GlassCircleButton(
                                RobocoIcons.Ellipsis, "More", {},
                                interactionSource = glassCircle,
                            )
                            GlassCircleButton(
                                RobocoIcons.Plus, "New session", {},
                                filled = RobocoTheme.palette.accent,
                                interactionSource = filledCircle,
                            )
                            GlassTextCapsule(
                                "Done", {},
                                emphasized = true,
                                interactionSource = capsule,
                            )
                            ProminentGlassButton("Next", {}, interactionSource = prominent)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val press = PressInteraction.Press(Offset.Zero)
        glassCircle.tryEmit(press)
        filledCircle.tryEmit(press)
        capsule.tryEmit(press)
        prominent.tryEmit(press)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun pressedLight() {
        pressedShot(Appearance.Light, "design/glass_buttons_pressed_light.png")
    }

    @Test
    fun pressedDark() {
        pressedShot(Appearance.Dark, "design/glass_buttons_pressed_dark.png")
    }
}
