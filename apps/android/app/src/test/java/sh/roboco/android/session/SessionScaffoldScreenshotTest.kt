package sh.roboco.android.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The open choreography's goldens (android-ui-parity #75): the ticket's
 * fixture — [SessionOpenFixture], the scaffold over the haze-source body
 * with [OpenRevealLayers], the transcript stand-in, the glass capsule and
 * ticket 52's header — in light and dark, at `w412dp-h900dp-xxhdpi`:
 *
 * - `SessionScaffold_holding` — the hold: the ×1.75 spinner at
 *   (195.5, 433.5)–(216.5, 454.5), the transcript invisible, the header
 *   and the capsule visible;
 * - `SessionScaffold_shown` — a settled screen: no loader, the transcript
 *   at alpha 1.
 *
 * Static states with the spinner's clock frozen (`ProvideGlyphClock(0)`),
 * so nothing animates while capturing. Recorded by `recordRoborazziDebug`,
 * verified by `verifyRoborazziDebug`; paths are relative to
 * `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionScaffoldScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ helpers

    private fun shot(appearance: Appearance, file: String, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) {
                // The loader's spinner is live in the holding state; a
                // frozen clock pins it at cycle 0 so the golden is stable.
                ProvideGlyphClock(frozenAtNanos = 0L) { content() }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().captureRoboImage("session/$file")
    }

    /** A settled screen: held, landed, rows in, reveal finished. */
    private fun shownReveal(): OpenReveal = OpenReveal().apply {
        begin(true)
        onLanded()
        onFirstRows()
        finishReveal()
    }

    // -------------------------------------------------------------- goldens

    @Test
    fun sessionScaffoldHoldingLight() {
        shot(Appearance.Light, "SessionScaffold_holding_light.png") {
            SessionOpenFixture(OpenReveal().apply { begin(true) })
        }
    }

    @Test
    fun sessionScaffoldHoldingDark() {
        shot(Appearance.Dark, "SessionScaffold_holding_dark.png") {
            SessionOpenFixture(OpenReveal().apply { begin(true) })
        }
    }

    @Test
    fun sessionScaffoldShownLight() {
        shot(Appearance.Light, "SessionScaffold_shown_light.png") {
            SessionOpenFixture(shownReveal())
        }
    }

    @Test
    fun sessionScaffoldShownDark() {
        shot(Appearance.Dark, "SessionScaffold_shown_dark.png") {
            SessionOpenFixture(shownReveal())
        }
    }

    // -------------------------------------------------------- pixel probes

    /**
     * The screen background is the palette's `background` in an empty area
     * (x 5, y 500: the transcript's start padding, clear of the text, the
     * loader and the capsule) — `#F3F3F5` light / `#060606` dark, ±2/255 —
     * in the holding state (one `setContent`; the appearance is switched
     * by a state write, as the header tests do).
     */
    @Test
    fun theBackgroundMatchesThePaletteInAnEmptyArea() {
        val reveal = OpenReveal().apply { begin(true) }
        var appearance by mutableStateOf(Appearance.Light)
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) { SessionOpenFixture(reveal) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val px = compose.onRoot().captureToImage().toPixelMap()
        assertBackgroundPixel(px, Palette.of(false).background, "light holding")

        compose.runOnUiThread { appearance = Appearance.Dark }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        val dark = compose.onRoot().captureToImage().toPixelMap()
        assertBackgroundPixel(dark, Palette.of(true).background, "dark holding")
    }

    private fun assertBackgroundPixel(px: PixelMap, background: Color, what: String) {
        val d = compose.density.density
        val c = px[(5f * d).toInt(), (500f * d).toInt()]
        assertTrue("$what background: $c vs $background", colorDistance(c, background) < 2f / 255f)
    }

    /** The max channel distance between two colours (pixel probes). */
    private fun colorDistance(a: Color, b: Color): Float =
        max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))
}
