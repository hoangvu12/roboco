package sh.roboco.android.design.glass

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.gallery.ScrollEdgeEngagedMode
import sh.roboco.android.design.gallery.ScrollEdgeEffectFixture

/**
 * The scroll-edge effect goldens (ticket 09): the ticket's fixture — the
 * pre-scrolled row list under the band, the centred "Sessions" title — at a
 * **fixed 24 dp status bar** (Robolectric has no real insets), in light and
 * dark, with the blur forced on (`blur = true`) and forced off (the
 * fallback). Recorded by `recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug`; paths are relative to `src/test/screenshots`.
 *
 * `blur` is passed explicitly both ways: the composition's default resolves
 * through `Glass.blurAvailable`, and Robolectric resolves the tier to
 * Fallback, so the default would silently record the fallback twice.
 *
 * Robolectric's native graphics may not execute the `RenderEffect` blur: if
 * the `blur = true` goldens come out identical to the fallback ones, they
 * are kept as-is — they still lock the geometry and the gradient — and the
 * blur itself is checked on an API 33+ emulator (the ticket's manual step
 * 1). Record with
 * `:app:recordRoborazziDebug --tests sh.roboco.android.design.glass.ScrollEdgeEffectScreenshotTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h400dp-xxhdpi")
class ScrollEdgeEffectScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, blur: Boolean, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                ScrollEdgeEffectFixture(
                    mode = ScrollEdgeEngagedMode.Always,
                    blur = blur,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun light() {
        shot(Appearance.Light, blur = true, "design/scroll_edge_effect_light.png")
    }

    @Test
    fun dark() {
        shot(Appearance.Dark, blur = true, "design/scroll_edge_effect_dark.png")
    }

    @Test
    fun fallbackLight() {
        shot(Appearance.Light, blur = false, "design/scroll_edge_effect_fallback_light.png")
    }

    @Test
    fun fallbackDark() {
        shot(Appearance.Dark, blur = false, "design/scroll_edge_effect_fallback_dark.png")
    }
}
