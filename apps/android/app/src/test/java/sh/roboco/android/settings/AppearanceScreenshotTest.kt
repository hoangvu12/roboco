package sh.roboco.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import sh.roboco.android.design.RobocoTheme

/**
 * The Appearance section goldens (android-ui-parity #96), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `settings/`:
 *
 * - `settings/appearance_light.png` — [AppearanceRowsColumn] under
 *   `RobocoTheme(Light)`, current System: "System" checked, light card;
 * - `settings/appearance_dark.png` — under `RobocoTheme(Dark)`, current
 *   Dark: "Dark" checked, dark card.
 *
 * The `appearance_light` golden is the side-by-side partner of
 * `2-settings.png`'s Appearance card: scale it to 460 px wide and align it
 * on the card top; the card edges and corners, the 52 dp row pitch, the
 * icon and title x (32…56, 72), the separator span (72…370), the
 * secondary-tinted half-circle/sun/moon glyphs and the `#5B43E8`
 * checkmark at ink centre 28.5 dp from the card's trailing edge must all
 * match within ±1.5 px.
 *
 * The host is the ticket's `w402dp-h400dp` phone at xxhdpi, font scale 1.
 * At rest nothing animates (ticket 70's highlight sits at 0), so the
 * goldens are byte-stable without freezing the clock.
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.settings.AppearanceScreenshotTest`,
 * verify with `./gradlew :app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h400dp-xxhdpi")
class AppearanceScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The section, the ticket's mounting, at the reference phone width. */
    private fun shotSection(appearance: Appearance, current: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                AppearanceRowsColumn(
                    current = current,
                    onPick = {},
                    modifier = Modifier
                        .width(402.dp)
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun appearanceLight() {
        shotSection(Appearance.Light, Appearance.System, "settings/appearance_light.png")
    }

    @Test
    fun appearanceDark() {
        shotSection(Appearance.Dark, Appearance.Dark, "settings/appearance_dark.png")
    }
}
