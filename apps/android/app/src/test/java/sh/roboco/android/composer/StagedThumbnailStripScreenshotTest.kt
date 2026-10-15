package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.Icon
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The staged-thumbnail goldens (android-ui-parity #88), recorded by
 * `:app:recordRoborazziDebug --tests sh.roboco.android.composer.StagedThumbnailStripScreenshotTest`
 * and verified by `verifyRoborazziDebug`:
 *
 *  - `composer_thumbs_3` — ticket 55's [ComposerSurface] at `p = 1`, the
 *    366 dp composer anchored bottom-centre 12 dp up over ticket 22's busy
 *    backdrop, with this ticket's real [StagedThumbnailStrip] holding 3
 *    fixture images, the 46 dp text stand-in and the 34 dp accent action
 *    circle (the same stand-ins the gallery section uses);
 *  - `composer_thumbs_8` — the same card with 8 fixture images: only 5
 *    tiles fit, so the trailing 24 dp edge fade is part of the golden.
 *
 * `ReduceMotion.debugOverride = true` (the ticket's rule — it does not
 * stop this strip's motion, and the fixtures are static) and
 * `Glass.forceFallback = true` (the golden convention; Robolectric blur is
 * not byte-stable), both reset in `@After`.
 *
 * The runner is the repo's Robolectric convention (`RobolectricTestRunner`
 * + compose rule), like every other screenshot test in the app; the
 * ticket's `AndroidJUnit4` note maps onto it (the ext-junit runner is an
 * androidTest-only dependency here).
 *
 * Paths are relative to `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `composer/`. The gallery section's own goldens
 * (`design/gallery/gallery_composer_thumbnails_{light,dark}.png`) are
 * captured automatically by ticket 22's parameterized
 * [sh.roboco.android.design.GalleryScreenshotTest] through the registry
 * line this ticket adds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h220dp-xhdpi")
class StagedThumbnailStripScreenshotTest {

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
        ReduceMotion.resetForTest()
    }

    /** The composer card with [count] fixture images in the strip. */
    @Composable
    private fun BoxScope.ThumbsComposer(count: Int) {
        val morph = remember { previewMorphState(1f, card = true) }
        ComposerSurface(
            progress = { 1f },
            chipsInteractive = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .width(366.dp),
            thumbnails = {
                StagedThumbnailStrip(
                    images = remember { (0 until count).map { fixtureStagedImage(it) } },
                    onRemove = {},
                    controlModifier = Modifier.composerControl(morph),
                )
            },
            text = { Box(Modifier.fillMaxWidth().height(46.dp)) },
            action = {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CapsuleShape)
                        .background(RobocoTheme.palette.accent)
                        .composerControl(morph),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(RobocoIcons.ArrowUp, tint = Color.White, sizePt = 15f, weight = SymbolWeight.Bold)
                }
            },
        )
    }

    private fun shot(appearance: Appearance, count: Int) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideHazeState {
                    GlassStage(
                        modifier = Modifier.size(390.dp, 220.dp),
                        height = 220.dp,
                        backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
                    ) { ThumbsComposer(count) }
                }
            }
        }
        compose.waitForIdle()
        // One frame: deterministic placement of the strip's initial state.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val mode = if (appearance == Appearance.Dark) "dark" else "light"
        compose.onRoot().captureRoboImage("composer/composer_thumbs_${count}_$mode.png")
    }

    // ----------------------------------------------------------- goldens

    @Test
    fun threeLight() {
        shot(Appearance.Light, 3)
    }

    @Test
    fun threeDark() {
        shot(Appearance.Dark, 3)
    }

    @Test
    fun eightLight() {
        shot(Appearance.Light, 8)
    }

    @Test
    fun eightDark() {
        shot(Appearance.Dark, 8)
    }
}
