package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * The accessory's goldens (android-ui-parity #27), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`).
 *
 * The ticket's step 8 fixture: a `w402dp-h400dp-xhdpi` frame of
 * `palette.background` holding the five count states — (0,0), (2,0),
 * (0,1), (0,3), (2,1) — each a 48 dp capsule with 21 dp side margins and
 * 8 dp between them, plus the inline (0,1) case. No `LocalHazeState` is
 * provided, so the glass resolves to ticket 07's Fallback tier: the light
 * golden's pixel probes (the acceptance criteria) are exactly the
 * fallback fill — white @0.94 over `#F3F3F5` — composited with the plate,
 * the plus, the label, the summary and the input dot.
 *
 * `ProvideGlyphClock(frozenAtNanos = 0L)` is nested **inside** the theme
 * so it overrides RobocoTheme's live clock: the spinner is deterministic
 * (the cycle-0 cell pattern). Rendered once per appearance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NewSessionAccessoryScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    NewSessionAccessoryFixture()
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h400dp-xhdpi")
    fun light() {
        shot(Appearance.Light, "shell/new_session_accessory_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h400dp-xhdpi")
    fun dark() {
        shot(Appearance.Dark, "shell/new_session_accessory_dark.png")
    }
}

/**
 * The step 8 fixture: the five count states plus the inline case, 21 dp
 * side margins, 8 dp between capsules, over the page background.
 */
@Composable
private fun NewSessionAccessoryFixture() {
    val palette = RobocoTheme.palette
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = NewSessionAccessoryMetrics.SideMargin),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NewSessionAccessory(counts = LiveCounts(), onTap = {})
        NewSessionAccessory(counts = LiveCounts(working = 2), onTap = {})
        NewSessionAccessory(counts = LiveCounts(awaiting = 1), onTap = {})
        NewSessionAccessory(counts = LiveCounts(awaiting = 3), onTap = {})
        NewSessionAccessory(counts = LiveCounts(working = 2, awaiting = 1), onTap = {})
        NewSessionAccessory(counts = LiveCounts(awaiting = 1), onTap = {}, inline = true)
    }
}
