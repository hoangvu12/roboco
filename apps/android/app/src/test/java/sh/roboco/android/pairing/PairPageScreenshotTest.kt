package sh.roboco.android.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.R
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.PairingTarget

/**
 * Ticket 72's goldens: [PairPageContent] (17's header row included) on
 * `background`, outside any sheet — the ticket's five states × light and
 * dark on the ticket's device (`w393dp-h852dp-xxhdpi`):
 *
 * - `pair_page_empty` — the placeholder, the Paste box (fixture), the
 *   hint;
 * - `pair_page_http` — `http://192.168.1.10:27655/pair#token=abc`:
 *   "Engine at 192.168.1.10:27655" + the cleartext note in `warning`;
 * - `pair_page_https` — the target only;
 * - `pair_page_redeeming` — the busy capsule ("Pairing…" + 08's
 *   spinner);
 * - `pair_page_error` — the Refused sentence in `danger`.
 *
 * The clock is frozen and exactly one frame is run before each capture,
 * so the spinner sits at step 0 and the goldens stay byte-stable
 * (PairingRootScreenshotTest's convention). Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.pairing.PairPageScreenshotTest`,
 * verified with `:app:verifyRoborazziDebug`; the files land under
 * `src/test/screenshots/pairing/`. Colour checks (card
 * `#FFFFFF`/`#111113`, footer `secondary`, warning `#A16207`/`#FACC15`,
 * error `#DC2626`/`#F87171`) are done by pixel-sampling these goldens
 * against the iOS Settings inset-grouped reference and the SignIn
 * capsule.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PairPageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(file: String, fixture: PairShotFixture, appearance: Appearance) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PairPageShotBody(fixture, appearance)
        }
        compose.waitForIdle()
        // One frame, then capture: 0 ms of animation time — the spinner
        // draws its step-0 spokes.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("pairing/$file")
    }

    // ------------------------------------------------------------- empty

    @Test
    fun emptyLight() {
        shot("pair_page_empty_light.png", ShotEmpty, Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun emptyDark() {
        shot("pair_page_empty_dark.png", ShotEmpty, Appearance.Dark)
    }

    // -------------------------------------------------------------- http

    @Test
    fun httpLight() {
        shot("pair_page_http_light.png", ShotHttp, Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun httpDark() {
        shot("pair_page_http_dark.png", ShotHttp, Appearance.Dark)
    }

    // ------------------------------------------------------------- https

    @Test
    fun httpsLight() {
        shot("pair_page_https_light.png", ShotHttps, Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun httpsDark() {
        shot("pair_page_https_dark.png", ShotHttps, Appearance.Dark)
    }

    // --------------------------------------------------------- redeeming

    @Test
    fun redeemingLight() {
        shot("pair_page_redeeming_light.png", ShotRedeeming, Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun redeemingDark() {
        shot("pair_page_redeeming_dark.png", ShotRedeeming, Appearance.Dark)
    }

    // ------------------------------------------------------------- error

    @Test
    fun errorLight() {
        shot("pair_page_error_light.png", ShotError, Appearance.Light)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun errorDark() {
        shot("pair_page_error_dark.png", ShotError, Appearance.Dark)
    }
}

/** One golden fixture. */
private data class PairShotFixture(
    val text: String,
    val target: PairingTarget?,
    val phase: PairPhase,
    val showPaste: Boolean,
)

private val ShotEmpty = PairShotFixture(
    text = "",
    target = null,
    phase = PairPhase.Idle,
    showPaste = true,
)

private val ShotHttp = PairShotFixture(
    text = "http://192.168.1.10:27655/pair#token=abc",
    target = PairingTarget(baseUrl = "http://192.168.1.10:27655", host = "192.168.1.10:27655"),
    phase = PairPhase.Idle,
    showPaste = false,
)

private val ShotHttps = PairShotFixture(
    text = "https://engine.example.ts.net/pair#token=abc",
    target = PairingTarget(baseUrl = "https://engine.example.ts.net", host = "engine.example.ts.net"),
    phase = PairPhase.Idle,
    showPaste = false,
)

private val ShotRedeeming = ShotHttp.copy(phase = PairPhase.Redeeming)

private val ShotError = ShotHttp.copy(
    phase = PairPhase.Error(PairMessage.Res(R.string.pair_error_refused)),
)

/** Renders one fixture: the page on `background`, outside any sheet. */
@Composable
private fun PairPageShotBody(fixture: PairShotFixture, appearance: Appearance) {
    val field = remember { TextFieldState(fixture.text) }
    RobocoTheme(appearance) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            PairPageContent(
                text = field,
                target = fixture.target,
                phase = fixture.phase,
                showPaste = fixture.showPaste,
                showScan = true,
                onClose = {},
                onPaste = {},
                onScan = {},
                onSubmit = {},
            )
        }
    }
}
