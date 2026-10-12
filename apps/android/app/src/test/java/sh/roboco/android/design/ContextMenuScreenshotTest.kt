package sh.roboco.android.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.glass.Glass

/**
 * The context-menu goldens (android-ui-parity #16), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`: the gallery
 * section long-pressed open on a session row (light and dark) and on the
 * 900 dp tall row (the overlap placement), on the ticket's
 * `w411dp-h891dp-xhdpi` device — 3 images under
 * `src/test/screenshots/design/`.
 *
 * [GalleryProviders] (capture mode) wraps the section in the real
 * [ContextMenuHost], so the goldens show the whole chrome: the dimmed
 * backdrop over the gallery, the lifted `SessionRow` preview (inset 8/1,
 * radius 16, `background` + `controlFill`) with its shadow, and ticket 15's
 * panel 8 dp below it. The lift and the panel are settled (advanceTimeBy
 * past both springs) so the goldens are byte-stable. The tick row is
 * frozen under capture (the section checks [LocalGalleryCapture]); the
 * glass panels draw ticket 07's fallback fill (`Glass.forceFallback`,
 * the golden convention), and Robolectric may not render the backdrop's
 * `RenderEffect` blur — the goldens then show the dim without it, which
 * the ticket accepts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class ContextMenuScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun forceFallbackTier() {
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    private fun shot(
        appearance: Appearance,
        rowTag: String,
        file: String,
        gesture: TouchInjectionScope.() -> Unit = { longClick() },
    ) {
        compose.setContent {
            GalleryProviders(appearance, capture = true) {
                ContextMenuGallerySection()
            }
        }
        compose.waitForIdle()
        // The long press needs virtual time for the 400 ms timeout; the
        // advance settles the lift (0.38 s) and 15's open spring (0.35 s)
        // before the capture.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(rowTag).performTouchInput { gesture() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun sessionRowLight() {
        shot(Appearance.Light, "ctx-row-1", "design/context_menu_light.png")
    }

    @Test
    fun sessionRowDark() {
        shot(Appearance.Dark, "ctx-row-1", "design/context_menu_dark.png")
    }

    @Test
    fun tallRowClipsThePreviewAndPlacesTheMenuWhereItFits() {
        // The 900 dp row starts ~500 dp down the page, so only its top is on
        // screen: the preview clips to the visible part and the menu, which
        // no longer fits below, opens above it. (The "neither side fits"
        // overlap is pinned by ContextMenuPlacementTest and checked on a
        // device by scrolling the row to fill the screen.) The touch sits
        // 50 dp below the row's top — inside the visible part, not at the
        // node's (off-screen) centre.
        shot(
            Appearance.Light,
            "ctx-tall",
            "design/context_menu_tall.png",
            gesture = { longClick(Offset(width / 2f, 100f)) },
        )
    }
}
