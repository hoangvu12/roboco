package sh.roboco.android.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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
import sh.roboco.android.design.glass.Glass

/**
 * The toast goldens (ticket 14), recorded by `recordRoborazziDebug` and
 * verified by `verifyRoborazziDebug`: the settled capsule over the striped
 * backdrop with the fake 80 dp chrome bar at the bottom, on the ticket's
 * `w393dp-h852dp-xxhdpi` device.
 *
 * The fixture is a **real [ToastHost]**: the placement math (anchor
 * 852 − 80 = 772 dp, bottom 760 dp, centred, capped width) is the
 * production one. The clock is frozen, the toast shown from the main
 * thread, then 1000 ms advanced — past the 0.4 s rise and the 350 ms
 * materialize, well inside the 4 s hold — so each shot is settled.
 *
 * `Glass.forceFallback = true` for the whole class so the goldens never
 * depend on how Robolectric reports hardware acceleration (the
 * `GlassSurfaceScreenshotTest` convention): the capsule draws the fallback
 * fill, rim and shadow. Paths are relative to the `roborazzi { outputDir }`
 * (`src/test/screenshots`), landing under `design/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ToastScreenshotTest {

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

    private fun shot(appearance: Appearance, text: String, action: String?, file: String) {
        val state = ToasterState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(appearance) {
                ToastHost(state, ToastAnnouncer { }) {
                    ToastChromeFixture(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle() // the chrome bar writes LocalBottomChromeTop here
        state.show(text, action) // main thread: synchronous
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun archivedUndoLight() {
        shot(Appearance.Light, "Archived", "Undo", "design/toast_archived_undo_light.png")
    }

    @Test
    fun archivedUndoDark() {
        shot(Appearance.Dark, "Archived", "Undo", "design/toast_archived_undo_dark.png")
    }

    @Test
    fun errorLight() {
        shot(Appearance.Light, "Couldn't open session. Try again.", null, "design/toast_error_light.png")
    }

    @Test
    fun errorDark() {
        shot(Appearance.Dark, "Couldn't open session. Try again.", null, "design/toast_error_dark.png")
    }

    @Test
    fun longTextLight() {
        // The 120-character label ellipsized inside window − 48 dp, the
        // action still fully visible.
        shot(Appearance.Light, ToastLongText, "Undo", "design/toast_long_text_light.png")
    }
}
