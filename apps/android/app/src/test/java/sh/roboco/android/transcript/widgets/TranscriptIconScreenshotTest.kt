package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The icon-widget goldens (ticket 124; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * the deterministic [TranscriptIconFixture] in light and dark at
 * `w393dp-h852dp-xxhdpi` (d = 3) on the `background` role box.
 *
 * What the goldens must show, side by side with an iPhone transcript: the
 * 18 dp tool glyphs in `secondary` (with `tool-terminal` in `danger`) and
 * `tool-bot` at 13.5 dp, all template-tinted and aspect-fit (the 1.5-unit
 * stroke becoming 1.125 dp); the untinted 15.75 / 16 dp file icons —
 * `code-blue`'s `#2563EB` strokes turning `#60A5FA` in dark,
 * `angular-component` and `document` unchanged (no dark art), the orange
 * Rust gear for `rust`; the 16 dp chip glyphs (`questionmark.bubble`
 * accent, `exclamationmark.triangle` danger, `arrow.triangle.branch`
 * tertiary) and the 15 dp inline-chip glyphs (`command`,
 * `wand.and.stars`, `photo` in soft) at their natural point size, centred;
 * and the 18 dp task boxes beside their "Task" labels — the unchecked
 * `#97979F` / `#6B6B72` outline, the checked `#5B43E8` / `#8B7CF6` fill.
 * The "unknown" (`waveform`) cell stays empty in both appearances.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptIconScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun reset() {
        // The fixture includes the unknown-name cell, so the log-once
        // memory starts clean for the golden run (and nothing leaks from
        // whatever ran earlier in this JVM).
        TranscriptIcons.resetForTest()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun iconWidgetLight() {
        shot(Appearance.Light, "transcript/icon_widget_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun iconWidgetDark() {
        shot(Appearance.Dark, "transcript/icon_widget_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    TranscriptIconFixture()
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }
}
