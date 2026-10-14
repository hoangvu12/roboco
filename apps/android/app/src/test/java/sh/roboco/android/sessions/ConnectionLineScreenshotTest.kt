package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
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
 * The connection line's goldens (android-ui-parity #50): every
 * examples-table fixture stacked (spinners, danger and warning dots, the
 * long fading INCOMPATIBLE sentence, the "+2 more" overflow), then the
 * offline Home header with its wifi-off glyph — light and dark — plus
 * the pressed `controlFill` item.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp), `w402dp-h900dp`
 * as the ticket pins, the glyph clock frozen at t = 0 so the spinner
 * lines are byte-stable. Roborazzi paths are relative to
 * `roborazzi { outputDir }` (`src/test/screenshots`), so the goldens
 * land at `src/test/screenshots/sessions/`; recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.ConnectionLineScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`.
 *
 * Check by eye against the ticket: no surface or border behind the
 * lines; spinner tints flip between light and dark; danger and warning
 * dots match the palette; the long INCOMPATIBLE caption fades out with
 * no "…" added.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class ConnectionLineScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------- goldens

    /** The examples-table fixtures, then the offline Home header. */
    private fun fixtures(appearance: Appearance) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Column {
                            ConnectionLineFixtures.all.forEach { ConnectionLine(it, onTap = {}) }
                            SectionHeaderRow(ConnectionLineFixtures.offlineHomeHeader, highlight = { 0f })
                        }
                    }
                }
            }
        }
        // The 500 ms entrance fade must be settled before the capture:
        // the clock auto-advances through it on idle.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test
    fun lightGolden() {
        fixtures(Appearance.Light)
        compose.onRoot().captureRoboImage("sessions/connection-line-light.png")
    }

    @Test
    fun darkGolden() {
        fixtures(Appearance.Dark)
        compose.onRoot().captureRoboImage("sessions/connection-line-dark.png")
    }

    @Test
    fun pressedGolden() {
        // The press → fill wiring itself is [ConnectionLineTest]'s; this
        // golden only draws it: the pointer stays down across the
        // capture, one controlFill rounded fill over the whole item.
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        ConnectionLine(ConnectionLineFixtures.mixed, onTap = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("connection-line").performTouchInput { down(center) }
        compose.onRoot().captureRoboImage("sessions/connection-line-pressed-light.png")
        compose.onNodeWithTag("connection-line").performTouchInput { up() }
        compose.waitForIdle()
    }
}
