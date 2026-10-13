package sh.roboco.android.shell

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.remember
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
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * Ticket 29's goldens: the search chrome fixture ("Search tab morph", the
 * gallery's frame) at fixed `W = 402`, `B = 24`, 420 dp tall, density 3
 * with the clock frozen: the accessory's live counts working 2 / awaiting
 * 1 (the frozen 12 dp spinner), Sessions as the previous tab.
 *
 *  - `p0` — the normal bar: ticket 26's pill and (this ticket's) search
 *    circle, the accessory above;
 *  - `p50` — the mid-morph row (entering: the field's chrome on, the
 *    magnifier between its two sizes);
 *  - `p1_empty` — the search row: the return circle with the Sessions
 *    glyph, the field capsule with the placeholder
 *    "Sessions, projects, branches", the accessory 8 above the field;
 *  - `p1_zeron` — with "zeron" typed: the text, the caret-free field and
 *    the clear button visible;
 *  - `p1_ime` — `imeLift = 28`: the row lifted 28 dp (8 ★ above the IME
 *    top), the accessory at alpha 0.5.
 *
 * Recorded by `:app:recordRoborazziDebug` and verified by
 * `:app:verifyRoborazziDebug`; the files land under
 * `src/test/screenshots/shell/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h420dp-xxhdpi")
class SearchMorphScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The fixture at a fixed morph position [p] — no animation to settle
     * (the field never auto-focuses: the fixture state's activation token
     * stays 0, so there is no caret), the text preset when non-empty.
     */
    private fun shot(file: String, p: Float, text: String = "", imeLift: Float = 0f) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    val state = remember {
                        SearchTabState().also {
                            if (text.isNotEmpty()) it.text.setTextAndPlaceCursorAtEnd(text)
                        }
                    }
                    SearchMorphFixture(
                        state = state,
                        progress = { p },
                        active = p > 0f,
                        minimizeProgress = { 0f },
                        imeLift = imeLift,
                        bottomInset = 24.dp,
                        height = 420.dp,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("shell/$file")
    }

    // ------------------------------------------------------------------ p0

    @Test
    fun p0Light() {
        shot("search_morph_p0_light.png", 0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h420dp-night-xxhdpi")
    fun p0Dark() {
        shot("search_morph_p0_dark.png", 0f)
    }

    // ---------------------------------------------------------------- p50

    @Test
    fun p50Light() {
        shot("search_morph_p50_light.png", 0.5f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h420dp-night-xxhdpi")
    fun p50Dark() {
        shot("search_morph_p50_dark.png", 0.5f)
    }

    // --------------------------------------------------------- p1, empty

    @Test
    fun p1EmptyLight() {
        shot("search_morph_p1_empty_light.png", 1f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h420dp-night-xxhdpi")
    fun p1EmptyDark() {
        shot("search_morph_p1_empty_dark.png", 1f)
    }

    // -------------------------------------------------------- p1, "zeron"

    @Test
    fun p1ZeronLight() {
        shot("search_morph_p1_zeron_light.png", 1f, text = "zeron")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h420dp-night-xxhdpi")
    fun p1ZeronDark() {
        shot("search_morph_p1_zeron_dark.png", 1f, text = "zeron")
    }

    // ------------------------------------------------- p1, IME lift = 28

    @Test
    fun p1ImeLiftLight() {
        shot("search_morph_p1_ime_light.png", 1f, text = "zeron", imeLift = 28f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h420dp-night-xxhdpi")
    fun p1ImeLiftDark() {
        shot("search_morph_p1_ime_dark.png", 1f, text = "zeron", imeLift = 28f)
    }
}
