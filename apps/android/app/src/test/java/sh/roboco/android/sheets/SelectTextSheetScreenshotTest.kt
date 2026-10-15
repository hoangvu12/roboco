package sh.roboco.android.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.sheet.rememberGlassSheetState

/**
 * The text-sheet goldens (ticket 127), recorded by `recordRoborazziDebug`
 * and verified by `verifyRoborazziDebug`: the ticket's fixtures, light and
 * dark, on the plain `Palette.background` backdrop the ticket's previews
 * use, at its 360 × 740 dp window at density 3, with the sheet **snapped**
 * to its detent (the snap runs in the section's own effect, which launches
 * after the sheet's present effect, so the captured frame is settled).
 *
 * - `select_text_all_selected`: the three-paragraph message at Large, the
 *   whole text selected on open (the clock is advanced a few frames so the
 *   first-frame focus landed and the wash is drawn; the handles and the
 *   toolbar are popups and do not render in Roborazzi — that is fine);
 * - `tool_detail_medium`: "Bash" at Medium with the grabber, the 40-line
 *   mono payload whose last line is 160 characters (the wrap);
 * - `select_text_font_1_3`: the non-mono text under a linear 1.3 font
 *   scale (`bodyScaled(16.5)` = 21.45 dp, unrounded).
 *
 * `Glass.forceFallback` and Reduce Motion follow the gallery-capture
 * convention (ticket 22) so the goldens stay byte-stable. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `sheets/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xxhdpi")
class SelectTextSheetScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    private fun shot(
        appearance: Appearance,
        file: String,
        mono: Boolean,
        density: Density? = null,
    ) {
        compose.setContent {
            val content: @Composable () -> Unit = {
                RobocoTheme(appearance) {
                    val palette = RobocoTheme.palette
                    Box(Modifier.fillMaxSize().background(palette.background)) {
                        val state = rememberGlassSheetState(
                            if (mono) SelectTextDefaults.MonoDetents else SelectTextDefaults.SansDetents,
                        )
                        SelectTextSheet(
                            text = if (mono) ToolPayload else ThreeParagraphs,
                            onDismiss = {},
                            title = if (mono) "Bash" else SelectTextDefaults.Title,
                            mono = mono,
                            state = state,
                        )
                        LaunchedEffect(state) { state.snapTo(state.detents.first()) }
                    }
                }
            }
            if (density != null) {
                CompositionLocalProvider(LocalDensity provides density) { content() }
            } else {
                content()
            }
        }
        compose.waitForIdle()
        // A few frames past the snap: the non-mono sheet's first-frame focus
        // request lands and the selection wash is drawn.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    // ------------------------------------------------------------ fixtures

    @Test
    fun selectTextAllSelectedLight() {
        shot(Appearance.Light, "sheets/select_text_all_selected_light.png", mono = false)
    }

    @Test
    fun selectTextAllSelectedDark() {
        shot(Appearance.Dark, "sheets/select_text_all_selected_dark.png", mono = false)
    }

    @Test
    fun toolDetailMediumLight() {
        shot(Appearance.Light, "sheets/tool_detail_medium_light.png", mono = true)
    }

    @Test
    fun toolDetailMediumDark() {
        shot(Appearance.Dark, "sheets/tool_detail_medium_dark.png", mono = true)
    }

    @Test
    fun selectTextFont13Light() {
        shot(
            Appearance.Light, "sheets/select_text_font_1_3_light.png", mono = false,
            density = ShotLinearDensity(density = 3f, fontScale = 1.3f),
        )
    }

    @Test
    fun selectTextFont13Dark() {
        shot(
            Appearance.Dark, "sheets/select_text_font_1_3_dark.png", mono = false,
            density = ShotLinearDensity(density = 3f, fontScale = 1.3f),
        )
    }

    /** The preview's three-paragraph message. */
    private val ThreeParagraphs =
        "Roboco connects directly to the engines you pair with it — no account, no cloud, no sync.\n\n" +
            "The transcript is painted straight from the Rust layout core, so the text in the list itself " +
            "cannot be selected. Select Text shows the whole message here, where the system selection " +
            "handles and the Copy toolbar work.\n\n" +
            "Done dismisses the sheet; the text stays selected while it is open."

    /** 40 mono lines, the last one 160 characters (the wrap fixture). */
    private val ToolPayload: String = buildString {
        for (i in 1..39) appendLine("line $i: $ roboco engine start --port ${8674 + i}")
        append("k".repeat(160))
    }
}

/**
 * A `Density` whose sp↔dp conversion is linear (the debug gallery's
 * `LinearFontScaleDensity`, kept local so the test does not reach into the
 * debug source set). Density 3 matches the xxhdpi window, so only the font
 * scale differs from the default shot.
 */
private class ShotLinearDensity(
    override val density: Float,
    override val fontScale: Float,
) : Density {
    override fun Dp.toSp(): TextUnit = (value / fontScale).sp

    override fun TextUnit.toDp(): Dp {
        check(type == TextUnitType.Sp) { "Only Sp can convert to Dp" }
        return (value * fontScale).dp
    }
}
