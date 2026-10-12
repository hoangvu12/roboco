package sh.roboco.android.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.FontGate
import sh.roboco.android.core.FontStatus

/**
 * The release-build gate and the fatal screen (Robolectric): a `Missing`
 * status replaces the content, `Ok` shows it, and `FontsMissingScreen`
 * renders with only the palette and the type scale provided — no
 * `LocalFonts`, because the fonts may be the very thing that is missing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FontGateHostTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun resetGate() {
        FontGate.check({ ByteArrayInputStream(byteArrayOf(0, 1, 0, 0)) }, debug = false, log = {})
    }

    @Test
    fun aLateFailureThroughTheStatusFlowSwapsToTheFatalScreen() {
        // Ticket 60: the host collects FontGate.statusFlow (as MainActivity
        // does now), so a failure after startup recomposes to the fatal
        // screen without recreating the activity.
        compose.setContent {
            CompositionLocalProvider(
                LocalPalette provides Palette.Light,
                LocalTypeScale provides TypeScale.from(Density(1f)),
            ) {
                val fontStatus by FontGate.statusFlow.collectAsState()
                FontGateHost(fontStatus) { BasicText("app") }
            }
        }
        compose.onNodeWithText("app").assertExists()
        compose.onNodeWithText(FONTS_MISSING_TITLE).assertDoesNotExist()

        FontGate.fail("fonts/Geist.ttf")
        compose.waitForIdle()

        compose.onNodeWithText(FONTS_MISSING_TITLE).assertExists()
        compose.onNodeWithText("fonts/Geist.ttf").assertExists()
        compose.onNodeWithText("app").assertDoesNotExist()
    }

    @Test
    fun missingStatusShowsTheFatalScreenInsteadOfTheContent() {
        compose.setContent {
            CompositionLocalProvider(
                LocalPalette provides Palette.Light,
                LocalTypeScale provides TypeScale.from(Density(1f)),
            ) {
                FontGateHost(FontStatus.Missing(listOf("fonts/Geist.ttf"))) {
                    BasicText("app")
                }
            }
        }
        compose.onNodeWithText(FONTS_MISSING_TITLE).assertExists()
        compose.onNodeWithText("fonts/Geist.ttf").assertExists()
        compose.onNodeWithText("app").assertDoesNotExist()
    }

    @Test
    fun okStatusShowsTheContent() {
        compose.setContent {
            CompositionLocalProvider(
                LocalPalette provides Palette.Light,
                LocalTypeScale provides TypeScale.from(Density(1f)),
            ) {
                FontGateHost(FontStatus.Ok) { BasicText("app") }
            }
        }
        compose.onNodeWithText("app").assertExists()
        compose.onNodeWithText(FONTS_MISSING_TITLE).assertDoesNotExist()
    }

    @Test
    fun fontsMissingScreenRendersWithoutLocalFonts() {
        // Only the palette and the type scale are provided — no
        // LocalFonts. If FontsMissingScreen touched it, the unfilled
        // composition local would throw right here.
        compose.setContent {
            CompositionLocalProvider(
                LocalPalette provides Palette.Dark,
                LocalTypeScale provides TypeScale.from(Density(1f)),
            ) {
                FontsMissingScreen(listOf("fonts/Geist.ttf", "fonts/GeistMono.ttf"))
            }
        }
        compose.onNodeWithText(FONTS_MISSING_TITLE).assertExists()
        compose.onNodeWithText(FONTS_MISSING_BODY).assertExists()
        compose.onNodeWithText("fonts/Geist.ttf").assertExists()
        compose.onNodeWithText("fonts/GeistMono.ttf").assertExists()
    }
}
