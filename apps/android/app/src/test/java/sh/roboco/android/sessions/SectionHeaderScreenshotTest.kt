package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * The foldable section header's goldens (android-ui-parity #39): the six
 * fixtures in light and dark, the first-frame collapsed render (the
 * chevron points right before any frame advance — no animation on first
 * composition), the 80 ms mid-flight chevron (analytically ≈ −63° under
 * `Motion.SectionChevron`, ζ 0.85 / 0.3 s settle-matched) and the pressed
 * `controlFill` row.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp), `w402dp-h480dp`
 * as the ticket pins, the glyph clock frozen at t = 0 so the spinner rows
 * are byte-stable. The light and dark goldens hold exactly the six
 * fixtures with `highlight = { 0f }` — the interactive gallery page has
 * its own taps. Roborazzi paths are relative to `roborazzi { outputDir }`
 * (`src/test/screenshots`), so the goldens land at
 * `src/test/screenshots/sessions/`; recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.SectionHeaderScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h480dp-xxhdpi")
class SectionHeaderScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------- goldens

    /** The six fixtures, unpressed, on the page background. */
    private fun fixtures(appearance: Appearance) {
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        Column {
                            SectionHeaderFixtures.all.forEach { SectionHeaderRow(it, highlight = { 0f }) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun lightGolden() {
        fixtures(Appearance.Light)
        compose.onRoot().captureRoboImage("sessions/section-header-light.png")
    }

    @Test
    fun darkGolden() {
        fixtures(Appearance.Dark)
        compose.onRoot().captureRoboImage("sessions/section-header-dark.png")
    }

    @Test
    fun firstCollapsedGolden() {
        // mainClock.autoAdvance = false before setContent: the mobile
        // fixture (collapsed = true) renders its first frame with the
        // chevron already pointing right, no animation, before any frame
        // advance.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        SectionHeaderRow(SectionHeaderFixtures.mobile, highlight = { 0f })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("sessions/section-header-first-collapsed-light.png")
    }

    @Test
    fun midAnimationGolden() {
        compose.mainClock.autoAdvance = false
        var collapsed by mutableStateOf(false)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        SectionHeaderRow(
                            SectionHeaderFixtures.p0.copy(collapsed = collapsed),
                            highlight = { 0f },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { collapsed = true }
        // One frame of launch alignment, then 80 ms: the spring sits at
        // ≈ −63° — between the 0° rest and the −90° settle. (No
        // waitForIdle between the flip and the frame: with the clock
        // paused, the pending recomposition runs on the advanced frame.)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("sessions/section-header-mid-light.png")
    }

    @Test
    fun pressedGolden() {
        // The press → highlight wiring itself is ticket 41's (tested
        // there); this golden only draws the fill at full highlight.
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        SectionHeaderRow(SectionHeaderFixtures.p0, highlight = { 1f })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("sessions/section-header-pressed-light.png")
    }
}
