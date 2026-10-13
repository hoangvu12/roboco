package sh.roboco.android.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * The session header's goldens (android-ui-parity #52): the ticket's
 * fixture — the reference title over the 20-paragraph transcript stand-in,
 * the back circle and ⋯, the soft edge engaged — at a **fixed 24 dp status
 * bar** (Robolectric has no real insets; `navBottom = 68`, band 84), in
 * light and dark, captured from a `Box(width × 240 dp)` so the frame shows
 * the bar plus some transcript:
 *
 * - `SessionHeader_long` — the reference title (overflowing, fading) and
 *   subtitle, back on, engaged (the fixture's 120 dp pre-scroll);
 * - `SessionHeader_short_noSubtitle` — "Fix login" with no subtitle (the
 *   title alone centred in the row);
 * - `SessionHeader_noBack` — `showBack = false` (the tablet detail column's
 *   root; the title still centred on the full width);
 * - `SessionHeader_narrow360` — a 360 dp frame, where the title cap shrinks
 *   to 224;
 * - `SessionHeader_reference` — the side-by-side match of
 *   `upstream/review-assets-pr-775:ios-bubble-{light,dark}.png`: 402 dp
 *   wide, `statusBarTop = 62` (navBottom = 106, band 122), subtitle
 *   "zeron @ MacBook Pro" and the transcript at its top (not engaged) —
 *   1 dp = 1 pt, circles/glyphs/title slot and baselines within 1 dp.
 *
 * Static positions (initial index/offset, no runtime scroll) so nothing
 * animates while capturing. Recorded by `recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug`; paths are relative to `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionHeaderScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val shotTag = "session-header-shot"

    private fun shot(
        appearance: Appearance,
        file: String,
        width: Dp = 412.dp,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.size(width, 240.dp).testTag(shotTag)) { content() }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(shotTag).captureRoboImage("session/$file")
    }

    @Test
    fun longLight() {
        shot(Appearance.Light, "SessionHeader_long_light.png") { SessionHeaderFixture() }
    }

    @Test
    fun longDark() {
        shot(Appearance.Dark, "SessionHeader_long_dark.png") { SessionHeaderFixture() }
    }

    @Test
    fun shortNoSubtitleLight() {
        shot(Appearance.Light, "SessionHeader_short_noSubtitle_light.png") {
            SessionHeaderFixture(title = "Fix login", subtitle = "")
        }
    }

    @Test
    fun shortNoSubtitleDark() {
        shot(Appearance.Dark, "SessionHeader_short_noSubtitle_dark.png") {
            SessionHeaderFixture(title = "Fix login", subtitle = "")
        }
    }

    @Test
    fun noBackLight() {
        shot(Appearance.Light, "SessionHeader_noBack_light.png") {
            SessionHeaderFixture(showBack = false)
        }
    }

    @Test
    fun noBackDark() {
        shot(Appearance.Dark, "SessionHeader_noBack_dark.png") {
            SessionHeaderFixture(showBack = false)
        }
    }

    @Test
    fun narrow360Light() {
        shot(Appearance.Light, "SessionHeader_narrow360_light.png", width = 360.dp) {
            SessionHeaderFixture()
        }
    }

    @Test
    fun narrow360Dark() {
        shot(Appearance.Dark, "SessionHeader_narrow360_dark.png", width = 360.dp) {
            SessionHeaderFixture()
        }
    }

    @Test
    fun referenceLight() {
        shot(Appearance.Light, "SessionHeader_reference_light.png", width = 402.dp) {
            SessionHeaderFixture(
                subtitle = "zeron @ MacBook Pro",
                statusBarTop = 62.dp,
                listState = rememberSessionHeaderFixtureListState(offsetDp = 0f),
            )
        }
    }

    @Test
    fun referenceDark() {
        shot(Appearance.Dark, "SessionHeader_reference_dark.png", width = 402.dp) {
            SessionHeaderFixture(
                subtitle = "zeron @ MacBook Pro",
                statusBarTop = 62.dp,
                listState = rememberSessionHeaderFixtureListState(offsetDp = 0f),
            )
        }
    }
}
