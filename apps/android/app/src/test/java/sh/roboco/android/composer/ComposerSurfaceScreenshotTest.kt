package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * The composer-surface goldens (android-ui-parity #55), recorded by
 * `:app:recordRoborazziDebug` and verified by `:app:verifyRoborazziDebug`:
 * the ticket's fixture — a 390 × 220 dp frame of ticket 22's busy
 * [GlassStage] backdrop (the composer is the backdrop's sibling, never
 * inside its `hazeSource`) with the 366 dp composer anchored at the
 * bottom centre, 12 dp up:
 *
 *  - `capsule` — `p = 0`: the 50 dp pill, "Message" placeholder, no chips;
 *  - `mid` — `p = 0.5` (the frozen mid-morph frame, expanding): the chips
 *    half clipped by the 25 dp band, half transparent, the text at the
 *    card width cut at the lerped frame;
 *  - `card` — `p = 1`: full-width text, the 50 dp toolbar row with `+`,
 *    three chips, the action;
 *  - `card_thumbs` — `p = 1` with the 68 dp thumbnail strip.
 *
 * `ReduceMotion.debugOverride = true` and `Glass.forceFallback = true`
 * (the GalleryCapture convention; Robolectric blur is not byte-stable),
 * reset in `@After`. Nothing animates — `p` is fixed per shot.
 *
 * The runner is the repo's Robolectric convention (`RobolectricTestRunner`
 * + compose rule), like every other screenshot test in the app; the
 * ticket's `AndroidJUnit4` note maps onto it (the ext-junit runner is an
 * androidTest-only dependency here).
 *
 * Paths are relative to `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `composer/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h220dp-xhdpi")
class ComposerSurfaceScreenshotTest {

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

    private fun shot(appearance: Appearance, file: String, p: Float, withThumbs: Boolean) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideHazeState {
                    GlassStage(
                        modifier = Modifier.size(390.dp, 220.dp),
                        height = 220.dp,
                        backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
                    ) {
                        ComposerFixture(p = p, withThumbs = withThumbs)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("composer/$file")
    }

    /**
     * The ticket's fixture at a fixed morph position [p]: the composer with
     * the stand-in slots of the gallery section (an empty box of 57's text
     * height, the "Message" placeholder, three chips, the accent action
     * circle, two thumbnails) and the real [ComposerAttachButton].
     */
    @Composable
    private fun BoxScope.ComposerFixture(p: Float, withThumbs: Boolean) {
        ComposerSurface(
            progress = { p },
            // Every shot except the capsule is an expanding morph's frame
            // (the target is the card), like the gallery's stages.
            chipsInteractive = p > 0f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .width(366.dp),
            thumbnails = { if (withThumbs) ShotThumbs() },
            text = { ShotText(p) },
            placeholder = { ShotPlaceholder() },
            attach = { ComposerAttachButton(onClick = {}) },
            chips = { ShotChips() },
            action = { ShotAction() },
        )
    }

    // ------------------------------------------------------ the stand-ins

    /** 57's text-slot height glide: `50 + (46 − 50)·p` dp. */
    private fun shotTextHeight(p: Float): Dp = (50f + (46f - 50f) * p).dp

    @Composable
    private fun ShotText(p: Float) {
        Box(Modifier.fillMaxWidth().height(shotTextHeight(p)))
    }

    @Composable
    private fun ShotPlaceholder() {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        BasicText(
            "Message",
            style = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.tertiary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    private fun ShotChips() {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (label in listOf("Opus 4.1", "High", "main")) {
                Box(
                    Modifier
                        .clip(CapsuleShape)
                        .background(palette.controlFill)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                ) {
                    BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(13.5f), palette.text))
                }
            }
        }
    }

    @Composable
    private fun ShotAction() {
        val palette = RobocoTheme.palette
        Box(
            Modifier.size(34.dp).clip(CapsuleShape).background(palette.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RobocoIcons.ArrowUp, tint = Color.White, sizePt = 15f, weight = SymbolWeight.Bold)
        }
    }

    @Composable
    private fun ShotThumbs() {
        val palette = RobocoTheme.palette
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(2) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.secondary),
                )
            }
        }
    }

    // ----------------------------------------------------------- goldens

    @Test
    fun capsuleLight() {
        shot(Appearance.Light, "composer_surface_capsule_light.png", p = 0f, withThumbs = false)
    }

    @Test
    fun capsuleDark() {
        shot(Appearance.Dark, "composer_surface_capsule_dark.png", p = 0f, withThumbs = false)
    }

    @Test
    fun midLight() {
        shot(Appearance.Light, "composer_surface_mid_light.png", p = 0.5f, withThumbs = false)
    }

    @Test
    fun midDark() {
        shot(Appearance.Dark, "composer_surface_mid_dark.png", p = 0.5f, withThumbs = false)
    }

    @Test
    fun cardLight() {
        shot(Appearance.Light, "composer_surface_card_light.png", p = 1f, withThumbs = false)
    }

    @Test
    fun cardDark() {
        shot(Appearance.Dark, "composer_surface_card_dark.png", p = 1f, withThumbs = false)
    }

    @Test
    fun cardThumbsLight() {
        shot(Appearance.Light, "composer_surface_card_thumbs_light.png", p = 1f, withThumbs = true)
    }

    @Test
    fun cardThumbsDark() {
        shot(Appearance.Dark, "composer_surface_card_thumbs_dark.png", p = 1f, withThumbs = true)
    }
}
