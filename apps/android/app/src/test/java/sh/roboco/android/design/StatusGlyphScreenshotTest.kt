package sh.roboco.android.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The glyph goldens (android-ui-parity #11): [StatusGlyphSampleRow] — the
 * five kinds at scale 1 plus the ×1.75 loader spinner — at frozen cycle
 * positions, recorded by `recordRoborazziDebug` and verified by
 * `verifyRoborazziDebug`. Paths are relative to the `roborazzi { outputDir }`
 * (`src/test/screenshots`).
 *
 * Every capture pins the environment the ticket prescribes:
 * `RobocoTheme(Appearance) { LocalReduceMotion provides false (or true) {
 * ProvideGlyphClock(frozenAtNanos) { … } } }` — reduce motion pinned so the
 * system animator setting under Robolectric cannot change the result, and a
 * frozen clock so no frame ever re-draws. The fixture is a 16-dp-padded row
 * of 4-dp-padded cells: Roborazzi crops captures to the node bounds, and the
 * grids deliberately overflow their 12-dp boxes (0.75 dp spinner, 1 dp
 * trailer) — the padding keeps the overflow inside the capture.
 *
 * What the goldens must show: the spinner tints flip light↔dark, the
 * trailer tints are identical in both, at t0 the spinner's TL cell is fully
 * opaque while ML/BL/BR are faint, at t04 (cycle 0.4) the bright cells have
 * stepped on, and the reduce golden at 300 ms shows the *t0* static pattern,
 * not the t04 one — a static capture at t0 cannot tell the two apart.
 * 256 × 72 dp at xhdpi (2 px/dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StatusGlyphScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, frozenAtNanos: Long, reduce: Boolean, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                CompositionLocalProvider(LocalReduceMotion provides reduce) {
                    ProvideGlyphClock(frozenAtNanos = frozenAtNanos) {
                        StatusGlyphSampleRow()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w256dp-h72dp-xhdpi")
    fun lightT0() {
        shot(Appearance.Light, 0L, false, "design/status-glyph-light-t0.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w256dp-h72dp-xhdpi")
    fun darkT0() {
        shot(Appearance.Dark, 0L, false, "design/status-glyph-dark-t0.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w256dp-h72dp-xhdpi")
    fun lightT04() {
        // Frozen at 300 ms of the 750 ms lap → cycle 0.4.
        shot(Appearance.Light, 300_000_000L, false, "design/status-glyph-light-t04.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w256dp-h72dp-xhdpi")
    fun lightReduceT04() {
        // Reduce motion ignores the clock time: the same static pattern as
        // t0 even though the clock is frozen at 300 ms.
        shot(Appearance.Light, 300_000_000L, true, "design/status-glyph-light-reduce-t04.png")
    }
}
