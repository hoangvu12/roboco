package sh.roboco.android.pairing

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

/**
 * Ticket 138's goldens: [QrScannerContent] over [FakeCameraPreview] on
 * the ticket's device (`w393dp-h852dp-xxhdpi`) — the four states × light
 * and dark:
 *
 * - `qr_scanner_hint` — Scanning, the hint caption, the drawn reticle;
 * - `qr_scanner_foreign` — Scanning, the foreign caption;
 * - `qr_scanner_denied` — the denied caption, no reticle stroke, the
 *   Open Settings capsule (`#E8E8EA` fill, `#060606` title);
 * - `qr_scanner_unavailable` — the unavailable caption, no stroke.
 *
 * Both modes wrap in `RobocoTheme(Appearance.Dark)` — the stage is always
 * dark (SHELL-D2) — so light and dark goldens must be **pixel-identical**;
 * the night qualifier only proves the values-night resources change
 * nothing. The clock is frozen and exactly one frame runs before each
 * capture (PairPageScreenshotTest's convention). Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.pairing.QrScannerScreenshotTest`,
 * verified with `:app:verifyRoborazziDebug`; the files land under
 * `src/test/screenshots/pairing/`. Colour checks (caption `#FFFFFF`,
 * reticle stroke `#E6FFFFFF`, ✕ glyph `#E8E8EA` on the `#111113` @0.94
 * fallback fill) are done by pixel-sampling these goldens against the iOS
 * image viewer's ✕ placement.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class QrScannerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(file: String, phase: ScannerPhase, foreign: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                QrScannerContent(
                    state = QrScannerUiState(phase, foreign),
                    onClose = {},
                    onOpenSettings = {},
                ) { FakeCameraPreview() }
            }
        }
        compose.waitForIdle()
        // One frame, then capture: 0 ms of animation time (nothing on the
        // screen animates, but the convention keeps the goldens
        // byte-stable).
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("pairing/$file")
    }

    // ------------------------------------------------------------- hint

    @Test
    fun hintLight() {
        shot("qr_scanner_hint_light.png", ScannerPhase.Scanning, foreign = false)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun hintDark() {
        shot("qr_scanner_hint_dark.png", ScannerPhase.Scanning, foreign = false)
    }

    // ---------------------------------------------------------- foreign

    @Test
    fun foreignLight() {
        shot("qr_scanner_foreign_light.png", ScannerPhase.Scanning, foreign = true)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun foreignDark() {
        shot("qr_scanner_foreign_dark.png", ScannerPhase.Scanning, foreign = true)
    }

    // ----------------------------------------------------------- denied

    @Test
    fun deniedLight() {
        shot("qr_scanner_denied_light.png", ScannerPhase.Denied, foreign = false)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun deniedDark() {
        shot("qr_scanner_denied_dark.png", ScannerPhase.Denied, foreign = false)
    }

    // ------------------------------------------------------ unavailable

    @Test
    fun unavailableLight() {
        shot("qr_scanner_unavailable_light.png", ScannerPhase.Unavailable, foreign = false)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun unavailableDark() {
        shot("qr_scanner_unavailable_dark.png", ScannerPhase.Unavailable, foreign = false)
    }
}
