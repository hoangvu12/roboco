package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle

/**
 * The composer-morph goldens (android-ui-parity #56), recorded by
 * `:app:recordRoborazziDebug` and verified by `:app:verifyRoborazziDebug`:
 * the **real** morph state driving ticket 55's [ComposerSurface] — the
 * focus spring, not a frozen `previewMorphState`, so the pipeline (driver
 * → [Motion.ComposerMorph] → `progress` → 55's layout) is what the golden
 * pins. The fixture is 55's screenshot fixture with a real
 * `BasicTextField(TextFieldState)` (one field for both states) carrying
 * [Modifier.composerInput] and the glass carrying [Modifier.composerMorphHost]:
 * a 390 × 220 dp frame of ticket 22's busy [GlassStage] backdrop, the 366 dp
 * composer anchored bottom-centre 12 dp up, a "Half-written thought" draft
 * and the two fixture chips (`model` "Opus 4.1", `effort` "High").
 *
 *  - `composer_morph_mid` — focus, then 100 ms of the spring
 *    (`autoAdvance = false`, `mainClock` stepping): the capsule is visibly
 *    mid-flight — the toolbar band half open, the chips fading in, the
 *    attach and action buttons gliding toward the toolbar;
 *  - `composer_morph_card` — settled at 1 (the composing card);
 *  - `composer_morph_capsule_draft` — blurred with the draft kept: the
 *    capsule resting with the two-line preview.
 *
 * `Glass.forceFallback = true` (the gallery convention; Robolectric blur is
 * not byte-stable) and no Reduce-Motion override — the spring must run.
 * The virtual clock makes the 100 ms frame deterministic, so record and
 * verify agree.
 *
 * Paths are relative to `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `composer/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h220dp-xhdpi")
class ComposerMorphScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        ReduceMotion.resetForTest()
    }

    /** The composer with the real morph state and the fixture slots. */
    @Composable
    private fun BoxScope.MorphComposerFixture(attach: (ComposerMorphState) -> Unit) {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        val morph = rememberComposerMorphState(alwaysCard = false, hasImages = { false })
        attach(morph)
        val field = remember { TextFieldState("Half-written thought") }
        ComposerSurface(
            progress = { morph.progress },
            chipsInteractive = morph.isCard,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .width(366.dp)
                .composerMorphHost(morph),
            text = {
                BasicTextField(
                    state = field,
                    textStyle = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.text),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
                    scrollState = morph.textScroll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .composerInput(morph),
                )
            },
            placeholder = {},
            attach = {
                Box(Modifier.composerControl(morph)) { ComposerAttachButton(onClick = {}) }
            },
            chips = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (label in listOf("Opus 4.1", "High")) {
                        Box(
                            Modifier
                                .clip(CapsuleShape)
                                .background(palette.controlFill)
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                                .composerControl(morph),
                        ) {
                            BasicText(
                                label,
                                style = uiStyle(Face.Sans, typeScale.fixed(13.5f), palette.text),
                            )
                        }
                    }
                }
            },
            action = {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CapsuleShape)
                        .background(palette.accent)
                        .composerControl(morph),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(RobocoIcons.ArrowUp, tint = Color.White, sizePt = 15f, weight = SymbolWeight.Bold)
                }
            },
        )
    }

    private fun shot(appearance: Appearance, file: String, drive: (ComposerMorphState) -> Unit) {
        var morph: ComposerMorphState? = null
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideHazeState {
                    GlassStage(
                        modifier = Modifier.size(390.dp, 220.dp),
                        height = 220.dp,
                        backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
                    ) {
                        MorphComposerFixture { morph = it }
                    }
                }
            }
        }
        compose.waitForIdle()
        // One frame first: the driver's launch costs one frame of alignment,
        // so "100 ms" means 100 ms of the animation, not of the clock.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        drive(requireNotNull(morph))
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/$file")
    }

    /** Focuses the field, then advances [ms] of the spring. */
    private fun focusThen(morph: ComposerMorphState, ms: Long) {
        compose.runOnUiThread { morph.focus() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    // ----------------------------------------------------------- goldens

    @Test
    fun midLight() {
        shot(Appearance.Light, "composer_morph_mid_light.png") { focusThen(it, 100) }
    }

    @Test
    fun midDark() {
        shot(Appearance.Dark, "composer_morph_mid_dark.png") { focusThen(it, 100) }
    }

    @Test
    fun cardLight() {
        shot(Appearance.Light, "composer_morph_card_light.png") { focusThen(it, 700) }
    }

    @Test
    fun cardDark() {
        shot(Appearance.Dark, "composer_morph_card_dark.png") { focusThen(it, 700) }
    }

    @Test
    fun capsuleDraftLight() {
        shot(Appearance.Light, "composer_morph_capsule_draft_light.png") { blurWithDraft(it) }
    }

    @Test
    fun capsuleDraftDark() {
        shot(Appearance.Dark, "composer_morph_capsule_draft_dark.png") { blurWithDraft(it) }
    }

    /** Focuses, settles, then blurs — the capsule rests with the draft. */
    private fun blurWithDraft(morph: ComposerMorphState) {
        focusThen(morph, 700)
        compose.runOnUiThread { morph.blur() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }
}
